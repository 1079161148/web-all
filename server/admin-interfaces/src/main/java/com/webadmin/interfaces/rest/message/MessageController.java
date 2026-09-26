package com.webadmin.interfaces.rest.message;

import com.webadmin.application.iam.MessageAppService;
import com.webadmin.application.iam.dto.MessageView;
import com.webadmin.application.iam.port.MessageStreamPort;
import com.webadmin.application.iam.port.MessageStreamPort.TicketOwner;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 消息中心接口。
 *
 * <h3>本 Controller 里唯一的"特殊成员"是 SSE 端点</h3>
 * 它在 {@code SecurityConfig} 的公开清单里，<b>但不等于匿名可访问</b>：
 * 连接必须出示有效票据（{@code GET /sse-ticket} 换取，一次性、30 秒、绑定身份），
 * 鉴权被移到了票据校验这一步。为什么必须这么做：
 * {@code EventSource} 不能携带自定义请求头（浏览器限制），
 * 而把长效访问令牌放进查询串会把它写进访问日志 —— 票据方案两者都避开。
 */
@Tag(name = "消息中心", description = "站内信收件箱、未读角标、实时推送与公告发布")
@RestController
@RequestMapping("/api/v1/messages")
@RequiredArgsConstructor
@Validated
public class MessageController {

    private final MessageAppService messageAppService;
    private final MessageStreamPort messageStreamPort;

    @Operation(operationId = "pageMyMessages", summary = "我的收件箱",
            description = "按创建时间倒序；isRead 可筛选未读/已读")
    @GetMapping
    public R<PageResult<MessageViewResponse>> page(@ParameterObject MessagePageRequest request) {
        PageResult<MessageView> page =
                messageAppService.myMessages(request.pageOrDefault(), request.sizeOrDefault(), request.isRead());
        return R.ok(new PageResult<>(
                page.records().stream().map(MessageViewResponse::from).toList(),
                page.total(), page.page(), page.size()));
    }

    @Operation(operationId = "getUnreadCount", summary = "未读数",
            description = "顶栏角标的数据源。收到实时推送信号后前端会重新拉取")
    @GetMapping("/unread-count")
    public R<Long> unreadCount() {
        return R.ok(messageAppService.unreadCount());
    }

    @Operation(operationId = "markMessageRead", summary = "标记单条已读",
            description = "幂等。传入不存在的或不属于自己的消息 ID 同样成功（不泄露存在性）")
    @PostMapping("/{id}/read")
    public R<Void> markRead(@PathVariable Long id) {
        messageAppService.markRead(id);
        return R.ok();
    }

    @Operation(operationId = "markAllMessagesRead", summary = "全部标记已读")
    @PostMapping("/read-all")
    public R<Integer> markAllRead() {
        return R.ok(messageAppService.markAllRead());
    }

    @Operation(operationId = "issueMessageStreamTicket", summary = "签发 SSE 连接票据",
            description = "一次性、30 秒有效、绑定当前用户。EventSource 不能带请求头，"
                    + "前端先取票据再用它开流")
    @GetMapping("/sse-ticket")
    public R<String> sseTicket() {
        return R.ok(messageAppService.issueStreamTicket());
    }

    @Operation(operationId = "streamMessages", summary = "消息实时流（SSE）",
            description = "公开端点，但必须出示有效票据。推送的只是刷新信号，"
                    + "客户端收到后重新拉取未读数与列表")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @Parameter(description = "连接票据", required = true)
            @RequestParam("ticket") @NotBlank String ticket) {
        TicketOwner owner = messageAppService.consumeStreamTicket(ticket);

        // 0L = 不超时：连接的生命周期由前端管理（断线重连），而非服务端定时切断
        SseEmitter emitter = new SseEmitter(0L);

        Runnable unregister = messageStreamPort.open(owner.tenantId(), owner.userId(),
                new MessageStreamPort.StreamConnection() {
                    @Override
                    public boolean send(String payload) {
                        try {
                            emitter.send(SseEmitter.event().data(payload));
                            return true;
                        } catch (IOException | IllegalStateException ex) {
                            // 连接已断（客户端关闭/网络中断）：让注册表移除死连接
                            return false;
                        }
                    }

                    @Override
                    public void close() {
                        emitter.complete();
                    }
                });

        // 连接生命周期的三个出口都必须注销，否则注册表只增不减
        emitter.onCompletion(unregister);
        emitter.onTimeout(unregister);
        emitter.onError(error -> unregister.run());

        // 先发一个连接成功事件：前端据此确认流已建立（而非静默挂起）
        try {
            emitter.send(SseEmitter.event().data("connected"));
        } catch (IOException ex) {
            // 客户端在握手后立刻断开：交给 onCompletion 清理
        }
        return emitter;
    }

    @Operation(operationId = "announceMessage", summary = "发布公告",
            description = "发给本租户全部用户（按接收者扇出，一人一行）并实时推送。"
                    + "返回实际落库的人数")
    @PreAuthorize("@ps.hasPermission('plt:message:publish')")
    @PostMapping("/announce")
    public R<Integer> announce(@Valid @RequestBody AnnounceRequest request) {
        return R.ok(messageAppService.announce(request.title(), request.content()));
    }

    // ==================================================================
    // Wire 契约
    // ==================================================================

    @Schema(description = "收件箱查询参数")
    public record MessagePageRequest(
            @Schema(description = "页码", defaultValue = "1") Integer page,
            @Schema(description = "每页条数", defaultValue = "20") Integer size,
            @Schema(description = "按已读状态筛选；不传=全部") Boolean isRead) {

        /** 带默认值的页码（record 的组件访问器是 page()/size()，这里刻意改名避免冲突）。 */
        public int pageOrDefault() {
            return page == null || page < 1 ? 1 : page;
        }

        public int sizeOrDefault() {
            return size == null || size < 1 || size > 100 ? 20 : size;
        }
    }

    @Schema(description = "发布公告请求")
    public record AnnounceRequest(
            @Schema(description = "标题", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank(message = "请输入公告标题")
            String title,

            @Schema(description = "正文")
            String content) {
    }

    @Schema(description = "站内信")
    public record MessageViewResponse(
            @Schema(description = "消息 ID") long id,
            @Schema(description = "标题") String title,
            @Schema(description = "正文") String content,
            @Schema(description = "类型：NOTICE/REMIND/SYSTEM") String msgType,
            @Schema(description = "是否已读") boolean isRead,
            @Schema(description = "阅读时间") java.time.Instant readTime,
            @Schema(description = "创建时间") java.time.Instant createTime) {

        public static MessageViewResponse from(MessageView view) {
            return new MessageViewResponse(view.id(), view.title(), view.content(),
                    view.msgType(), view.isRead(), view.readTime(), view.createTime());
        }
    }
}
