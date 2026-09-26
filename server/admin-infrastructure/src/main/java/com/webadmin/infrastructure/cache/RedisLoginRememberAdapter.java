package com.webadmin.infrastructure.cache;

import com.webadmin.application.iam.port.LoginRememberPort;
import java.time.Duration;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * "免登录天数"会话标记的 Redis 实现。
 *
 * <h3>Key 设计</h3>
 * <pre>{@code webadmin:{tenantId}:remember:{sessionId}}</pre>
 * 值是纯数字（天数），{@code redis-cli get} 直接可读。
 * <b>不写"不记住"的记录</b>：键存在即"记住 N 天"，不存在即"不记住" ——
 * 少一种状态，少一类"0 与非 0 语义混淆"的 bug。
 *
 * <h3>故障语义：fail-soft</h3>
 * 读写失败都<b>不抛出</b>：这个标记只影响"刷新后 Cookie 是否带有效期"，
 * 不影响鉴权正确性。
 * <ul>
 *   <li>写失败：该会话刷新后 Cookie 降级为会话级（用户下次要重新登录）</li>
 *   <li>读失败：同上，按"不记住"处理</li>
 * </ul>
 * 两者都不会造成任何安全降级（不会把"不记住"变成"记住"），
 * 因此没必要让登录/刷新失败 —— 这与刷新令牌适配器的 fail-closed 取向
 * 是<b>刻意不同</b>的：那里的失败必须拒绝，因为那是安全边界。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLoginRememberAdapter implements LoginRememberPort {

    private static final String PREFIX = "webadmin:";
    private static final String SEGMENT = ":remember:";

    private final StringRedisTemplate redis;

    @Override
    public void mark(long tenantId, String sessionId, int days, Duration ttl) {
        if (sessionId == null || sessionId.isBlank() || days <= 0) {
            return;
        }
        try {
            redis.opsForValue().set(key(tenantId, sessionId), String.valueOf(days), ttl);
        } catch (RuntimeException ex) {
            log.warn("记录免登录天数失败（该会话刷新后 Cookie 将降级为会话级）tenantId={} days={}",
                    tenantId, days, ex);
        }
    }

    @Override
    public OptionalInt find(long tenantId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return OptionalInt.empty();
        }
        try {
            String raw = redis.opsForValue().get(key(tenantId, sessionId));
            if (raw == null || raw.isBlank()) {
                return OptionalInt.empty();
            }
            int days = Integer.parseInt(raw.trim());
            return days > 0 ? OptionalInt.of(days) : OptionalInt.empty();
        } catch (RuntimeException ex) {
            // 含 NumberFormatException（值被外部改坏）：按"不记住"处理
            log.debug("读取免登录天数失败或值非法，按不记住处理 tenantId={}", tenantId, ex);
            return OptionalInt.empty();
        }
    }

    private static String key(long tenantId, String sessionId) {
        return PREFIX + tenantId + SEGMENT + sessionId;
    }
}
