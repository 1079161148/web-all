package com.webadmin.interfaces.rest.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监控大屏的 WebSocket 推送端点（/ws/monitor?ticket=xxx）。
 *
 * <h3>服务端同样是"批量推"</h3>
 * 每帧生成 4~8 条 tick，120ms 一次（约 40 条/秒）—— 与前端 400ms 节流
 * 是同一个思想在两端的体现：单条推 = 放大网络与序列化开销。
 *
 * <h3>断线补偿</h3>
 * 服务端维护 6000 条环形历史；客户端发现 seq 空洞后回发
 * {"type":"backfill","fromSeq":N}，这里从环形缓冲取缺失段回发 ——
 * 与 REST 补拉接口语义一致，但省一次建连。
 *
 * <h3>多实例</h3>
 * 当前广播只达本实例的会话。多实例部署时的扩展点与 SSE 相同：
 * tick 写 Redis Pub/Sub，各实例订阅后写本地会话 —— 接口已按此预留。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonitorWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ArrayDeque<JsonNode> history = new ArrayDeque<>();
    private long seq = 0;

    private static final String[] REGIONS = {"华东", "华北", "华南", "西南", "东北", "海外"};

    @Scheduled(fixedRate = 120)
    public void push() {
        if (sessions.isEmpty()) {
            return;
        }
        List<JsonNode> batch = new ArrayList<>();
        int size = 4 + (int) (Math.random() * 5);
        for (int i = 0; i < size; i++) {
            seq += 1;
            ObjectNode tick = objectMapper.createObjectNode();
            tick.put("seq", seq);
            tick.put("ts", System.currentTimeMillis() - (long) (Math.random() * 2000));
            tick.put("region", REGIONS[(int) (Math.random() * REGIONS.length)]);
            tick.put("amount", 20 + (int) (Math.random() * 800));
            tick.put("ok", Math.random() > 0.03);
            batch.add(tick);
            history.addLast(tick);
            if (history.size() > 6000) {
                history.removeFirst();
            }
        }
        broadcast(batch);
    }

    private synchronized void broadcast(List<JsonNode> ticks) {
        String payload = frame("ticks", ticks);
        for (WebSocketSession session : sessions.values()) {
            try {
                if (session.isOpen()) {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(payload));
                    }
                }
            } catch (Exception ex) {
                log.warn("[WS] 发送失败 sessionId={}", session.getId(), ex);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode request = objectMapper.readTree(message.getPayload());
            if ("backfill".equals(request.path("type").asText())) {
                long fromSeq = request.path("fromSeq").asLong();
                List<JsonNode> missing = new ArrayList<>();
                for (JsonNode tick : history) {
                    if (tick.path("seq").asLong() > fromSeq) {
                        missing.add(tick);
                    }
                }
                if (!missing.isEmpty()) {
                    synchronized (session) {
                        session.sendMessage(new TextMessage(frame("ticks", missing)));
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("[WS] 处理客户端消息失败", ex);
        }
    }

    private String frame(String type, List<JsonNode> ticks) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", type);
        ArrayNode array = node.putArray("ticks");
        ticks.forEach(array::add);
        return node.toString();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("[WS] 监控连接建立 sessionId={} 当前连接数={}", session.getId(), sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("[WS] 监控连接关闭 sessionId={} 剩余连接数={}", session.getId(), sessions.size());
    }
}