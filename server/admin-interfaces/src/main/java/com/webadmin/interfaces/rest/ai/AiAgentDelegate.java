package com.webadmin.interfaces.rest.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webadmin.application.ai.AiAgentService;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 指挥中心的 Agent 循环（Tool Calling 编排）。
 *
 * <h3>循环结构</h3>
 * <ol>
 *   <li>带 tools schema 请求上游（非流式 —— tool_calls 需要完整结构）；</li>
 *   <li>finish_reason=tool_calls → 执行真实工具，向浏览器推 step 事件
 *       （工具/参数/结果对指挥者透明），结果以 role=tool 回传模型进下一轮；</li>
 *   <li>否则为最终回答 → 流式 delta → done。</li>
 * </ol>
 * 轮数上限是循环类 Agent 的标准护栏：模型可能陷入"调用→失败→再调用"的打转。
 */
@Component
public class AiAgentDelegate {

    private static final int MAX_ROUNDS = 4;

    private final AiAgentService agentService;
    private final AiChatDelegate.AiProviderConfig config;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AiAgentDelegate(AiAgentService agentService, AiChatDelegate.AiProviderConfig config) {
        this.agentService = agentService;
        this.config = config;
    }

    private String systemPrompt(String platformContext) {
        return "你是本后台管理系统的 AI 指挥官。用户用自然语言下达指令，你通过调用工具操纵系统"
                + "（查工单/抢单/办结/放回/看审计）。规则：1.先用工具获取事实再行动，不要凭空猜工单 ID；"
                + "2.写操作前确认目标工单存在且状态允许；3.工具失败时如实说明，同一失败调用不要重复超过一次；"
                + "4.完成后用简洁中文汇报：做了什么、结果如何、还剩什么。当前平台上下文：" + platformContext;
    }

    /** 执行 Agent 循环（SSE：meta → step… → delta… → done）。 */
    public SseEmitter stream(
            String instruction, String context, long tenantId,
            org.springframework.security.core.context.SecurityContext securityContext) {
        SseEmitter emitter = new SseEmitter(Duration.ofSeconds(config.timeoutSeconds() * 2L).toMillis());
        Thread.ofVirtual().start(() -> {
            try {
                emitter.send(SseEmitter.event().name("meta")
                        .data(config.apiKey().isBlank() ? "demo" : "real"));
                if (config.apiKey().isBlank()) {
                    emitter.send(SseEmitter.event().name("delta")
                            .data("【演示模式】指挥中心需要配置 AI Key 后才能调度工具。"));
                } else {
                    runAgentLoop(instruction, context, tenantId, securityContext, emitter);
                }
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception ex) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(String.valueOf(ex.getMessage())));
                } catch (Exception ignored) {
                    // 客户端已断开
                }
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    /** 请求上游一轮（非流式；withTools 决定是否携带工具清单）。 */
    private JsonNode requestChat(List<ObjectNode> messages, boolean withTools) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.model());
        if (withTools) {
            body.set("tools", objectMapper.readTree(agentService.toolsSchemaJson()));
        }
        ArrayNode array = body.withArray("messages");
        for (ObjectNode message : messages) {
            array.add(message.deepCopy());
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/chat/completions"))
                .header("Authorization", "Bearer " + config.apiKey())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<java.io.InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String payload = reader.lines().reduce("", (a, b) -> a + b);
            JsonNode root = objectMapper.readTree(payload);
            if (response.statusCode() != 200) {
                throw new IllegalStateException("AI 提供方返回 " + response.statusCode());
            }
            return root.path("choices").path(0);
        }
    }

    /** Agent 主循环：tool_calls → 执行 → 回传 → 再推理，直至最终回答或达到轮数上限。 */
    private void runAgentLoop(
            String instruction, String context, long tenantId,
            org.springframework.security.core.context.SecurityContext securityContext,
            SseEmitter emitter) throws Exception {
        // ⚠️ 两类 ThreadLocal 都不进虚拟线程，必须从请求线程快照注入：
        //   TenantContext —— 不注入则所有工具拿到"缺少租户上下文"；
        //   SecurityContextHolder —— 不注入则写操作拿到"没有登录用户上下文"
        //   （抢单/办结要以当前用户身份执行条件 UPDATE）。
        com.webadmin.common.tenant.TenantContext.set(tenantId);
        org.springframework.security.core.context.SecurityContextHolder.setContext(securityContext);
        try {
            runAgentRounds(instruction, context, emitter);
        } finally {
            com.webadmin.common.tenant.TenantContext.clear();
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private void runAgentRounds(String instruction, String context, SseEmitter emitter)
            throws Exception {
        List<ObjectNode> messages = new ArrayList<>();
        ObjectNode system = objectMapper.createObjectNode();
        messages.add(system);
        system.put("role", "system");
        system.put("content", systemPrompt(context));
        ObjectNode user = objectMapper.createObjectNode();
        messages.add(user);
        user.put("role", "user");
        user.put("content", instruction);

        for (int round = 0; round < MAX_ROUNDS; round++) {
            JsonNode choice = requestChat(messages, true);
            JsonNode message = choice.path("message");
            boolean wantsTools = "tool_calls".equals(choice.path("finish_reason").asText(""))
                    && message.has("tool_calls");

            if (!wantsTools) {
                String content = message.path("content").asText("");
                for (int i = 0; i < content.length(); i += 4) {
                    emitter.send(SseEmitter.event().name("delta")
                            .data(content.substring(i, Math.min(i + 4, content.length()))));
                }
                return;
            }

            // 记录 assistant 消息（含 tool_calls 原始结构），再逐个执行真实工具
            ObjectNode assistantMsg = objectMapper.createObjectNode();
            messages.add(assistantMsg);
            assistantMsg.put("role", "assistant");
            if (message.path("content").isTextual()) {
                assistantMsg.put("content", message.path("content").asText(""));
            }
            assistantMsg.set("tool_calls", message.withArray("tool_calls").deepCopy());

            for (JsonNode call : message.withArray("tool_calls")) {
                String toolName = call.path("function").path("name").asText("");
                String argsRaw = call.path("function").path("arguments").asText("{}");
                Map<String, Object> args;
                try {
                    args = objectMapper.readValue(argsRaw,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                            });
                } catch (Exception ex) {
                    args = Map.of();
                }
                String result = agentService.executeTool(toolName, args);

                // 执行过程对指挥者透明：工具/参数/结果都推给前端时间线
                emitter.send(SseEmitter.event().name("step").data(objectMapper.writeValueAsString(
                        Map.of("tool", toolName, "args", argsRaw, "result", result))));

                ObjectNode toolMsg = objectMapper.createObjectNode();
                messages.add(toolMsg);
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", call.path("id").asText(""));
                toolMsg.put("content", result);
            }

            // 轮数用尽仍要工具 → 如实告知而不是静默截断
            if (round == MAX_ROUNDS - 1) {
                emitter.send(SseEmitter.event().name("delta")
                        .data("（已达单次指令的执行轮数上限，剩余操作未执行 —— 请拆分指令后重试）"));
            }
        }
    }
}
