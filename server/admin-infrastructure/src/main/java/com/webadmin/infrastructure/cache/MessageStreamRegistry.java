package com.webadmin.infrastructure.cache;

import com.webadmin.application.iam.port.MessageStreamPort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * SSE 连接注册表（<b>本实例</b>的在线连接）。
 *
 * <h3>它只管"连在我这台机器上的连接"</h3>
 * SSE 连接是实例内存状态：用户可能连在集群的任何一台机器上。
 * 跨实例的投递由 Redis Pub/Sub 完成（见 {@link RedisMessageStreamAdapter}），
 * 本类只负责把"到达本实例的信号"写给对应的连接。
 *
 * <h3>并发与死连接</h3>
 * <ul>
 *   <li>同一用户可能有<b>多条</b>连接（多个标签页）→ 每个键存列表</li>
 *   <li>写入失败（连接已断）即从列表移除并关闭 —— 死连接不清，
 *       会越积越多且每次投递都在浪费时间</li>
 *   <li>SSE 也有浏览器侧的断线（代理超时等），前端会重连；
 *       旧连接的清理必须可靠，否则注册表只增不减</li>
 * </ul>
 */
@Slf4j
@Component
public class MessageStreamRegistry {

    private final Map<String, List<MessageStreamPort.StreamConnection>> connections =
            new ConcurrentHashMap<>();

    private final StringRedisTemplate redis;

    public MessageStreamRegistry(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String key(long tenantId, long userId) {
        return tenantId + ":" + userId;
    }

    /** 登记连接，返回用于注销的句柄。 */
    public Runnable register(long tenantId, long userId, MessageStreamPort.StreamConnection connection) {
        String key = key(tenantId, userId);
        List<MessageStreamPort.StreamConnection> list =
                connections.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        list.add(connection);
        log.debug("SSE 连接建立 tenantId={} userId={} 当前连接数={}", tenantId, userId, list.size());
        return () -> {
            list.remove(connection);
            if (list.isEmpty()) {
                connections.remove(key, list);
            }
        };
    }

    /**
     * 把信号写给该用户的全部本地连接。
     *
     * @return 实际投递成功的连接数
     */
    public int deliver(long tenantId, long userId, String payload) {
        List<MessageStreamPort.StreamConnection> list = connections.get(key(tenantId, userId));
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (MessageStreamPort.StreamConnection connection : list) {
            try {
                if (connection.send(payload)) {
                    delivered++;
                } else {
                    connection.close();
                    list.remove(connection);
                }
            } catch (RuntimeException ex) {
                log.debug("SSE 投递失败，移除死连接 userId={}", userId, ex);
                list.remove(connection);
            }
        }
        return delivered;
    }

    /** 生成票据（UUID 足够：30 秒一次性、且消费时即删除）。 */
    public String newTicketToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 票据的 Redis 键（票据跨实例有效：任意实例都能验）。 */
    public String ticketKey(String ticket) {
        return "webadmin:msg:ticket:" + ticket;
    }

    /** 广播频道（跨实例投递信号）。 */
    public static String broadcastChannel() {
        return "webadmin:msg:push";
    }

    /** 发布跨实例信号。 */
    public void publish(long tenantId, long userId) {
        try {
            redis.convertAndSend(broadcastChannel(), tenantId + ":" + userId);
        } catch (RuntimeException ex) {
            // 推送只是"该刷新了"的信号：发布失败不影响落库的事实，
            // 用户端最多晚一些看到角标变化。记录即可，不抛
            log.warn("发布消息推送信号失败 tenantId={} userId={}", tenantId, userId, ex);
        }
    }
}
