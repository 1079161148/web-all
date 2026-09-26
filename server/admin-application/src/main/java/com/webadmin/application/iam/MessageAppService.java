package com.webadmin.application.iam;

import com.webadmin.application.iam.dto.MessageView;
import com.webadmin.application.iam.port.MessagePort;
import com.webadmin.application.iam.port.MessageStreamPort;
import com.webadmin.application.iam.port.MessageStreamPort.TicketOwner;
import com.webadmin.common.api.PageResult;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.domain.iam.repository.UserRepository;
import com.webadmin.domain.shared.UserId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 消息中心应用服务（站内信 / 公告 / 实时推送）。
 *
 * <h3>推送只是信号，不是数据</h3>
 * 公告的投递分两步：<b>落库</b>（扇出一人一行，事实来源）与
 * <b>推送信号</b>（跨实例广播"该刷新了"）。前端收到信号后重新拉取未读数 ——
 * 推送丢失只造成几秒延迟，永不造成状态不一致。
 * 若把消息内容本身塞进推送通道，"丢了就是丢了"，
 * 未读角标会永久错下去 —— 那种 bug 在多实例 + 断线重连下几乎必然出现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageAppService {

    private final MessagePort messagePort;
    private final MessageStreamPort messageStreamPort;
    private final UserRepository userRepository;
    private final com.webadmin.application.security.CurrentUserPort currentUserPort;

    /** 收件箱分页（只看自己的消息；租户隔离由拦截器保证）。 */
    public PageResult<MessageView> myMessages(int page, int size, Boolean isRead) {
        long userId = currentUserId();
        return messagePort.page(userId, page, size, isRead);
    }

    /** 未读数（角标）。 */
    public long unreadCount() {
        return messagePort.unreadCount(currentUserId());
    }

    /** 标记单条已读。别人的消息 ID 静默成功（不泄露存在性）。 */
    public void markRead(long messageId) {
        messagePort.markRead(currentUserId(), messageId);
    }

    /** 全部已读。 */
    public int markAllRead() {
        return messagePort.markAllRead(currentUserId());
    }

    /**
     * 发布公告：发给本租户全部用户并逐个推送实时信号。
     *
     * @return 实际送达（落库）的人数
     */
    public int announce(String title, String content) {
        long tenantId = TenantContext.require();
        long senderId = currentUserId();

        List<Long> receiverIds = userRepository.findAllIds();
        if (receiverIds.isEmpty()) {
            return 0;
        }

        List<MessageView> created = messagePort.fanOut(
                tenantId, receiverIds, "NOTICE", title, content, senderId);
        // fanOut 保证返回顺序与 receiverIds 一致 → 按下标对应接收者。
        // 推送的只是"该刷新了"的信号（不含消息体），见类注释
        for (int i = 0; i < created.size(); i++) {
            messageStreamPort.notifyUser(tenantId, receiverIds.get(i));
        }
        log.info("公告已发布 tenantId={} 发送者={} 接收者数={} 标题={}",
                tenantId, senderId, created.size(), title);
        return created.size();
    }

    /** 为当前用户签发 SSE 连接票据。 */
    public String issueStreamTicket() {
        com.webadmin.application.security.CurrentUser current =
                currentUserPort.requireCurrentUser();
        return messageStreamPort.issueTicket(current.tenantId(), current.userId());
    }

    /**
     * 用票据建立 SSE 连接。
     *
     * <p>由控制器在公开端点里调用：票据即凭证 ——
     * 能出示未使用过的有效票据，等价于"刚刚通过认证"。
     */
    public TicketOwner consumeStreamTicket(String ticket) {
        TicketOwner owner = messageStreamPort.consumeTicket(ticket);
        if (owner == null) {
            throw new BizException(CommonErrorCode.UNAUTHENTICATED, "连接票据无效或已过期");
        }
        return owner;
    }

    private long currentUserId() {
        return currentUserPort.requireCurrentUser().userId();
    }
}
