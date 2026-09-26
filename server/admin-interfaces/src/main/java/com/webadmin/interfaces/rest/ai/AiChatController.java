package com.webadmin.interfaces.rest.ai;

import com.webadmin.application.ai.AiChatOrchestrator;
import com.webadmin.interfaces.rest.interceptor.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 对话（后端代理流式端点）。
 *
 * <p>响应是 {@code text/event-stream}：meta（real/demo）→ delta… → done。
 * 前端用 fetch + ReadableStream 消费（EventSource 只支持 GET，无法带对话体）。
 * 上报面向所有登录用户；按用户限流 —— AI 调用按 token 计费，滥用即真金白银。
 */
@Tag(name = "AI 对话", description = "AI 客服（后端代理 DeepSeek/OpenAI 兼容接口，流式）")
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiChatController {

    private final AiChatDelegate delegate;

    public record ChatRequest(
            String sessionId,
            @NotEmpty List<ChatMessageBody> messages) {
    }

    /** content 为字符串或多模态内容块数组（OpenAI 协议两种形态），后端原样透传。 */
    public record ChatMessageBody(String role, Object content) {
    }

    @Operation(operationId = "aiChatStream", summary = "AI 对话（SSE 流式）",
            description = "未配置 Key 时进入演示模式（meta=demo）：流式 UX 完整、回答明示来源；"
                    + "工单上下文在两种模式下都来自真实数据库")
    @RateLimit(limit = 20, windowSeconds = 60, scope = RateLimit.Scope.USER)
    @PreAuthorize("@ps.hasPermission('ai:chat:use')")
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@Valid @RequestBody ChatRequest request) {
        List<AiChatOrchestrator.ChatTurn> turns = request.messages().stream()
                .map(m -> new AiChatOrchestrator.ChatTurn(m.role(), m.content()))
                .toList();
        return delegate.stream(turns);
    }
}
