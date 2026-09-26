package com.webadmin.infrastructure.cache;

import com.webadmin.application.iam.port.MessageStreamPort;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 消息推送通道的 Redis 实现（票据 + 跨实例广播）。
 *
 * <p>结构见 {@link MessageStreamPort} 与 {@link MessageStreamRegistry} 的说明：
 * 票据放 Redis（任意实例可验），广播走 Pub/Sub（到达每台实例），
 * 连接表留在本实例内存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMessageStreamAdapter implements MessageStreamPort {

    private static final Duration TICKET_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redis;
    private final MessageStreamRegistry registry;

    @Override
    public String issueTicket(long tenantId, long userId) {
        String ticket = registry.newTicketToken();
        try {
            redis.opsForValue().set(registry.ticketKey(ticket),
                    tenantId + ":" + userId, TICKET_TTL);
        } catch (RuntimeException ex) {
            // 票据写不进去就无法建立 SSE 连接 —— 这是可感知的功能故障，向上抛
            log.error("签发 SSE 票据失败", ex);
            throw ex;
        }
        return ticket;
    }

    @Override
    public TicketOwner consumeTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        try {
            // 取出即删除（原子）：票据用一次就作废。
            // 重放同一张票会被拒绝 —— 这是"短时票据"安全性的另一半
            String raw = redis.opsForValue().getAndDelete(registry.ticketKey(ticket));
            if (raw == null || raw.isBlank()) {
                return null;
            }
            int idx = raw.indexOf(':');
            if (idx <= 0) {
                return null;
            }
            return new TicketOwner(
                    Long.parseLong(raw.substring(0, idx)),
                    Long.parseLong(raw.substring(idx + 1)));
        } catch (RuntimeException ex) {
            log.warn("消费 SSE 票据失败（按无效处理）", ex);
            return null;
        }
    }

    @Override
    public Runnable open(long tenantId, long userId, StreamConnection connection) {
        return registry.register(tenantId, userId, connection);
    }

    @Override
    public void notifyUser(long tenantId, long userId) {
        registry.publish(tenantId, userId);
    }
}
