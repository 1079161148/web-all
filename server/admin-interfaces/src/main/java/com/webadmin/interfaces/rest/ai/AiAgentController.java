package com.webadmin.interfaces.rest.ai;

import com.webadmin.application.ai.AiAgentService;
import com.webadmin.application.ai.AiChatOrchestrator;
import com.webadmin.interfaces.rest.interceptor.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 指挥中心（Agent Tool Calling）。
 *
 * 与聊天端点的区别：带 tools 清单 —— 模型可驱动系统真实动作
 * （抢单/办结/放回/查审计）。授权模型即授权该工具集，权限码独立，
 * 限流更紧（写操作真实生效，不能被脚本刷）。
 */
@Tag(name = "AI 指挥中心", description = "自然语言指挥系统动作（Agent Tool Calling，SSE 流式）")
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiAgentController {

    private final AiAgentDelegate delegate;
    private final AiChatOrchestrator orchestrator;

    public record AgentRequest(
            String sessionId,
            @NotBlank String instruction) {
    }

    @Operation(operationId = "aiAgentStream", summary = "AI 指挥（Agent 循环，SSE 流式）",
            description = "事件序列：meta（real/demo）→ step（每次工具调用：工具/参数/结果）→ "
                    + "delta（最终回答）→ done。工具执行会真实改变系统状态")
    @RateLimit(limit = 10, windowSeconds = 60, scope = RateLimit.Scope.USER)
    @PreAuthorize("@ps.hasPermission('ai:agent:use')")
    @PostMapping(value = "/agent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter agent(@Valid @RequestBody AgentRequest request) {
        // 三类快照都在请求线程取（ThreadLocal 不进虚拟线程）：租户、安全上下文、平台上下文
        return delegate.stream(
                request.instruction(),
                orchestrator.platformContextSnapshot(),
                com.webadmin.common.tenant.TenantContext.require(),
                org.springframework.security.core.context.SecurityContextHolder.getContext());
    }
}