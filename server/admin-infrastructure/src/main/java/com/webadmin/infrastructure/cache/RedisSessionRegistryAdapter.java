package com.webadmin.infrastructure.cache;

import com.webadmin.application.iam.port.AuthStoreUnavailableException;
import com.webadmin.application.iam.port.SessionRegistryPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 会话注册表的 Redis 实现。
 *
 * <h3>Key 设计</h3>
 * <pre>{@code
 *   webadmin:{tenantId}:sess:{userId}:{sessionId}   → 会话内容（字符串，见下方格式）
 *   webadmin:{tenantId}:sess:idx:{userId}           → 该用户会话 ID 的 SET（索引）
 * }</pre>
 * <b>为什么要索引 SET 而不是 SCAN</b>：{@code SCAN} 在生产上是反模式
 * （游标遍历、KEYS 会阻塞、集群下语义更复杂），而"列某人的会话""踢某人全部会话"
 * 是这里最核心的两个操作。用一份 SET 索引把它们变成确定性的一次读 + 批量删。
 *
 * <h3>值格式</h3>
 * <pre>{@code ver|username|ip|loginEpochMilli|lastActiveEpochMilli|userAgent}</pre>
 * 与权限缓存同样不引 JSON：字段少、可读、{@code redis-cli get} 直接能看。
 *
 * <p>分隔符安全性：{@code username} 受账号字符集约束、{@code ip} 由服务端解析
 * （长度受限）、{@code userAgent} 与 {@code ip} 在写入前会做<b>清洗</b>
 * （去分隔符与换行、截断长度）—— 见 {@link #sanitize}。
 * 若不清洗，一个带 {@code |} 的 User-Agent 就能把记录结构打乱，
 * 表现是"某台设备的会话看起来属于另一个用户"，属于必须防住的一类问题。
 *
 * <h3>故障语义</h3>
 * {@link #find} 在 Redis 故障时<b>抛出</b> {@link AuthStoreUnavailableException}，
 * 而不是返回空 —— 返回空等于"全站瞬间登出"，原因见该异常的注释。
 * 其余方法（register/touch/revoke/list）失败只记日志：
 * 它们的失败不会把用户挡在门外（最坏是"本次没能注册会话"，由登录流程重试）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisSessionRegistryAdapter implements SessionRegistryPort {

    private static final String PREFIX = "webadmin:";
    private static final String SESSION_SEGMENT = ":sess:";
    private static final String INDEX_SEGMENT = ":sess:idx:";
    private static final String FIELD_SEPARATOR = "|";
    private static final int FIELD_COUNT = 6;

    /** 索引比会话本身多活 1 天：成员先过期是正常的（读取时顺手清理），反之会丢索引。 */
    private static final Duration INDEX_EXTRA_TTL = Duration.ofDays(1);

    /**
     * {@code touch} 的写入节流窗口。
     *
     * <p>若每个请求都写一次 lastActive，Redis 会立刻成为瓶颈（热路径上的写放大）。
     * 60 秒的粒度对"最后活跃时间"这个展示型字段完全够用。
     */
    private static final Duration TOUCH_INTERVAL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;

    @Override
    public void register(SessionRecord session, Duration ttl) {
        try {
            String key = sessionKey(session.tenantId(), session.userId(), session.sessionId());
            redis.opsForValue().set(key, serialize(session), ttl);
            String indexKey = indexKey(session.tenantId(), session.userId());
            redis.opsForSet().add(indexKey, session.sessionId());
            redis.expire(indexKey, ttl.plus(INDEX_EXTRA_TTL));
        } catch (RuntimeException ex) {
            log.error("注册会话失败（该会话的令牌将无法通过鉴权校验，用户需重新登录）。"
                    + "tenantId={} userId={} sessionId={}",
                    session.tenantId(), session.userId(), session.sessionId(), ex);
        }
    }

    @Override
    public Optional<SessionRecord> find(long tenantId, long userId, String sessionId) {
        String raw;
        try {
            raw = redis.opsForValue().get(sessionKey(tenantId, userId, sessionId));
        } catch (RuntimeException ex) {
            // 关键：抛出而不是返回空。返回空会被上层理解为"会话已注销"→ 全站登出
            throw new AuthStoreUnavailableException(
                    "读取会话失败 tenantId=" + tenantId + " userId=" + userId, ex);
        }
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(deserialize(sessionId, tenantId, userId, raw));
    }

    @Override
    public boolean touch(long tenantId, long userId, String sessionId) {
        try {
            String key = sessionKey(tenantId, userId, sessionId);
            String raw = redis.opsForValue().get(key);
            if (raw == null || raw.isBlank()) {
                return false;
            }
            SessionRecord current = deserialize(sessionId, tenantId, userId, raw);
            Instant now = Instant.now();
            if (current.lastActive() != null
                    && Duration.between(current.lastActive(), now).compareTo(TOUCH_INTERVAL) < 0) {
                // 节流窗口内不写：热路径上的写放大比"时间戳精确到秒"重要得多
                return false;
            }
            Long remaining = redis.getExpire(key, TimeUnit.SECONDS);
            Duration ttl = remaining == null || remaining <= 0
                    ? TOUCH_INTERVAL
                    : Duration.ofSeconds(remaining);
            SessionRecord updated = new SessionRecord(current.sessionId(), tenantId, userId,
                    current.username(), current.tokenVersion(), current.ip(), current.userAgent(),
                    current.loginTime(), now);
            redis.opsForValue().set(key, serialize(updated), ttl);
            return true;
        } catch (RuntimeException ex) {
            // 只更新"最后活跃时间"失败不影响鉴权，记日志即可
            log.debug("刷新会话活跃时间失败（不影响鉴权）sessionId={}", sessionId, ex);
            return false;
        }
    }

    @Override
    public void revoke(long tenantId, long userId, String sessionId) {
        try {
            redis.delete(sessionKey(tenantId, userId, sessionId));
            redis.opsForSet().remove(indexKey(tenantId, userId), sessionId);
            log.info("会话已注销 tenantId={} userId={} sessionId={}", tenantId, userId, sessionId);
        } catch (RuntimeException ex) {
            log.error("注销会话失败（该会话在令牌过期前仍然可用，需人工关注）。sessionId={}", sessionId, ex);
        }
    }

    @Override
    public int revokeAll(long tenantId, long userId) {
        try {
            Set<String> sessionIds = redis.opsForSet().members(indexKey(tenantId, userId));
            if (sessionIds == null || sessionIds.isEmpty()) {
                return 0;
            }
            List<String> keys = sessionIds.stream()
                    .map(id -> sessionKey(tenantId, userId, id))
                    .toList();
            redis.delete(keys);
            redis.delete(indexKey(tenantId, userId));
            log.info("已注销用户全部会话 tenantId={} userId={} 会话数={}", tenantId, userId, keys.size());
            return keys.size();
        } catch (RuntimeException ex) {
            log.error("注销用户全部会话失败，需人工关注。tenantId={} userId={}", tenantId, userId, ex);
            return 0;
        }
    }

    @Override
    public List<SessionRecord> list(long tenantId, long userId) {
        Set<String> sessionIds;
        try {
            sessionIds = redis.opsForSet().members(indexKey(tenantId, userId));
        } catch (RuntimeException ex) {
            log.warn("读取在线会话失败 tenantId={} userId={}", tenantId, userId, ex);
            return List.of();
        }
        if (sessionIds == null || sessionIds.isEmpty()) {
            return List.of();
        }

        List<SessionRecord> sessions = new ArrayList<>(sessionIds.size());
        List<String> stale = new ArrayList<>();
        for (String sessionId : sessionIds) {
            try {
                String raw = redis.opsForValue().get(sessionKey(tenantId, userId, sessionId));
                if (raw == null || raw.isBlank()) {
                    // 键已过期但索引还留着 → 顺手清理，否则列表会越用越脏
                    stale.add(sessionId);
                    continue;
                }
                sessions.add(deserialize(sessionId, tenantId, userId, raw));
            } catch (RuntimeException ex) {
                log.debug("读取单个会话失败，已跳过 sessionId={}", sessionId, ex);
            }
        }
        if (!stale.isEmpty()) {
            try {
                redis.opsForSet().remove(indexKey(tenantId, userId), stale.toArray());
            } catch (RuntimeException ex) {
                log.debug("清理悬空会话索引失败（下次读取会重试）", ex);
            }
        }
        sessions.sort(Comparator.comparing(SessionRecord::lastActive,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return sessions;
    }

    // ------------------------------------------------------------------

    private static String sessionKey(long tenantId, long userId, String sessionId) {
        return PREFIX + tenantId + SESSION_SEGMENT + userId + ":" + sessionId;
    }

    private static String indexKey(long tenantId, long userId) {
        return PREFIX + tenantId + INDEX_SEGMENT + userId;
    }

    private static String serialize(SessionRecord session) {
        return session.tokenVersion()
                + FIELD_SEPARATOR + sanitize(session.username(), 64)
                + FIELD_SEPARATOR + sanitize(session.ip(), 64)
                + FIELD_SEPARATOR + epochMilli(session.loginTime())
                + FIELD_SEPARATOR + epochMilli(session.lastActive())
                + FIELD_SEPARATOR + sanitize(session.userAgent(), 200);
    }

    private static SessionRecord deserialize(String sessionId, long tenantId, long userId, String raw) {
        String[] parts = raw.split("\\" + FIELD_SEPARATOR, -1);
        if (parts.length < FIELD_COUNT) {
            // 结构不符：按"版本号不匹配"处理（即视为已吊销）而不是抛异常。
            // 让一个格式异常的记录把鉴权路径打挂，比"要求重新登录"严重得多。
            log.warn("会话记录格式异常（按已吊销处理）sessionId={} 段数={}", sessionId, parts.length);
            return new SessionRecord(sessionId, tenantId, userId, "", -1L, "", "", null, null);
        }
        return new SessionRecord(
                sessionId,
                tenantId,
                userId,
                parts[1],
                parseLong(parts[0], -1L),
                parts[2],
                parts[5],
                parseInstant(parts[3]),
                parseInstant(parts[4]));
    }

    /** 清洗：去掉分隔符与控制字符并截断 —— 见类注释中"User-Agent 打乱结构"的说明。 */
    private static String sanitize(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace(FIELD_SEPARATOR, "/")
                .replace("\r", " ")
                .replace("\n", " ");
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private static long epochMilli(Instant instant) {
        return instant == null ? 0L : instant.toEpochMilli();
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static Instant parseInstant(String value) {
        long millis = parseLong(value, 0L);
        return millis <= 0 ? null : Instant.ofEpochMilli(millis);
    }
}
