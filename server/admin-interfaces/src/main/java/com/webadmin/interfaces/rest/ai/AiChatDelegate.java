package com.webadmin.interfaces.rest.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.webadmin.application.ai.AiChatOrchestrator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * AI 流式端点的协议适配（OpenAI wire 协议 ↔ SSE ↔ 浏览器）。
 *
 * <h3>为什么这层在 interfaces 而不是 application</h3>
 * 它的全部职责都是<b>协议</b>：OpenAI 的 /chat/completions 请求体、
 * 上游 SSE 的 data: 帧解析、浏览器 SSE（SseEmitter）封装 ——
 * 换一种上游协议或推送通道只改这一层；application 层
 * （AiChatOrchestrator）保持纯 Java、无 Jackson 依赖，
 * 架构门禁（分层约束）因此不被破坏。
 *
 * <h3>降级是明示的</h3>
 * meta 事件先发 real / demo —— 前端徽标如实展示，绝不把演示回答
 * 伪装成模型回答。
 */
@Component
public class AiChatDelegate {

    /** 提供方配置（AI Key 留空 = 演示模式；Key 只在服务端）。 */
    @ConfigurationProperties(prefix = "webadmin.ai")
    public record AiProviderConfig(
            String apiKey,
            String baseUrl,
            String model,
            int timeoutSeconds
    ) {
        public AiProviderConfig {
            if (apiKey == null) {
                apiKey = "";
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://api.deepseek.com";
            }
            if (model == null || model.isBlank()) {
                model = "deepseek-chat";
            }
            if (timeoutSeconds <= 0) {
                timeoutSeconds = 60;
            }
        }
    }

    private final AiChatOrchestrator orchestrator;
    private final AiProviderConfig config;

    // 与 MonitorWebSocketHandler 同一惯例：本项目未注册 ObjectMapper bean，
    // Jackson 无状态的 mapper 直接实例化即可
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public AiChatDelegate(AiChatOrchestrator orchestrator, AiProviderConfig config) {
        this.orchestrator = orchestrator;
        this.config = config;
    }

    /**
     * 发起流式对话。事件序列：meta（real/demo）→ delta… → done → complete。
     * 上游失败/客户端断开以 error 事件告知后收尾 —— 半开的流比报错的流更坑。
     */
    public SseEmitter stream(List<AiChatOrchestrator.ChatTurn> messages) {
        orchestrator.validate(messages);
        // ⚠️ 上下文快照必须在请求线程取（TenantContext 是 ThreadLocal，
        // 虚拟线程里拿不到 —— 实测会变成"上下文暂不可用"）
        final String context = orchestrator.platformContextSnapshot();
        SseEmitter emitter = new SseEmitter(Duration.ofSeconds(config.timeoutSeconds()).toMillis());
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            try {
                emitter.send(SseEmitter.event().name("meta")
                        .data(config.apiKey().isBlank() ? "demo" : "real"));
                if (config.apiKey().isBlank()) {
                    streamDemo(messages, context, emitter);
                } else {
                    streamFromProvider(messages, context, emitter);
                }
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
            } catch (Exception ex) {
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(friendlyError(ex)));
                } catch (Exception ignored) {
                    // 客户端已断开
                }
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    /** 上游异常 → 用户可读的信息（原始技术细节进日志，不进气泡）。 */
    private String friendlyError(Exception ex) {
        if (ex instanceof java.net.http.HttpTimeoutException
                || (ex.getMessage() != null && ex.getMessage().contains("timed out"))) {
            return "AI 服务响应超时（上游无响应），请稍后重试";
        }
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        if (message.contains("401")) {
            return "AI 服务认证失败（API Key 无效或已过期），请检查服务端配置";
        }
        if (message.contains("429")) {
            return "AI 服务限流中，请稍后再试";
        }
        return "AI 服务异常：" + message;
    }

    /** 真实模式：OpenAI 兼容 /chat/completions stream=true → 逐行解析转发。 */
    private void streamFromProvider(
            List<AiChatOrchestrator.ChatTurn> messages, String context, SseEmitter emitter)
            throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.model());
        body.put("stream", true);
        ArrayNode array = body.withArray("messages");
        ObjectNode system = array.addObject();
        system.put("role", "system");
        system.put("content", orchestrator.systemPrompt(context));
        for (AiChatOrchestrator.ChatTurn turn : messages) {
            ObjectNode node = array.addObject();
            node.put("role", turn.role());
            // content 原样透传（字符串或多模态内容块数组）—— 协议由前端契约定义
            node.set("content", objectMapper.valueToTree(turn.content()));
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
        if (response.statusCode() != 200) {
            throw new IllegalStateException("AI 提供方返回 " + response.statusCode());
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) {
                    return;
                }
                JsonNode node = objectMapper.readTree(payload);
                String delta = node.path("choices").path(0).path("delta").path("content").asText("");
                if (!delta.isEmpty()) {
                    emitter.send(SseEmitter.event().name("delta").data(delta));
                }
            }
        }
    }

    /** 演示模式：确定性生成流式回答（约 30ms/块，复现真实模型的打字节奏）。 */
    private void streamDemo(
            List<AiChatOrchestrator.ChatTurn> messages, String context, SseEmitter emitter)
            throws Exception {
        String question = orchestrator.textOf(messages.get(messages.size() - 1).content());
        String answer = orchestrator.demoAnswer(question, context);
        for (int i = 0; i < answer.length(); i += 3) {
            int end = Math.min(i + 3, answer.length());
            emitter.send(SseEmitter.event().name("delta").data(answer.substring(i, end)));
            Thread.sleep(30);
        }
    }
}
