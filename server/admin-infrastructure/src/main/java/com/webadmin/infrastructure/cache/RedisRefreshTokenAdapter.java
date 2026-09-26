package com.webadmin.infrastructure.cache;

import com.webadmin.application.iam.port.AuthStoreUnavailableException;
import com.webadmin.application.iam.port.RefreshTokenPort;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 刷新令牌的 Redis 实现（轮换 + 重放检测）。
 *
 * <h3>Key 设计</h3>
 * <pre>{@code
 *   webadmin:rt:{hash}          → familyId|tenantId|userId|sessionId   （有效令牌）
 *   webadmin:rt:used:{hash}     → 1                                    （已轮换标记）
 *   webadmin:rt:fam:{familyId}  → 该族全部 hash 的 SET                  （整族吊销）
 *   webadmin:{tenantId}:rt:user:{userId} → 该用户全部 familyId 的 SET   （改密后全部失效）
 * }</pre>
 *
 * <h3>⚠️ 为什么前三类键<b>不带租户段</b>（与项目其它缓存刻意不同）</h3>
 * 刷新接口是<b>认证入口</b>：调用方只带着一个不透明的刷新令牌，
 * <b>此时没有任何可信的租户上下文</b>（请求头里的租户不可信，令牌本身也不含租户信息）。
 * 若把租户放进键，就必须先知道租户才能查 —— 逻辑上成了循环依赖。
 *
 * <p>隔离性由两点保证，而不是靠键名：
 * <ol>
 *   <li>键是令牌的 <b>SHA-256</b>（256 位随机值），不可猜测 ⇒ 无法通过"构造键"越租户</li>
 *   <li>记录里存着 {@code tenantId}，取出后由应用层重建租户上下文并校验租户状态 ——
 *       真正的隔离发生在这里</li>
 * </ol>
 * 也就是说：这里的隔离依据是"<b>持有那个令牌</b>"。这一点必须写清楚，
 * 否则后人会"顺手补上租户段"，直接把刷新功能改坏（表现是"刷新永远失败"）。
 *
 * <p>用户级的索引（{@code rt:user:}）反而带租户：调用它的场景（改密/停用/强制下线）
 * 都已经在租户上下文里，带上更安全，也便于按租户运维。
 *
 * <h3>为什么"已用"标记要与有效令牌分开存</h3>
 * 攻击者可能先于合法用户使用被窃取的令牌，此时旧令牌已被轮换；
 * 合法用户随后拿着旧令牌来刷新 —— 只有"已用"标记还活着，才能识别出这是<b>重放</b>。
 * 若轮换时直接删除旧 key，这个识别机会就永久失去了（两者都表现为"未知令牌"）。
 *
 * <h3>故障语义</h3>
 * {@link #find} 故障时抛 {@link AuthStoreUnavailableException}：
 * 刷新校验<b>不能</b>在没有存储的情况下"乐观放行"，否则 Redis 一挂，
 * 已被吊销的刷新令牌又能用了。宁可让用户重新登录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRefreshTokenAdapter implements RefreshTokenPort {

    private static final String PREFIX = "webadmin:";
    private static final String TOKEN_SEGMENT = "rt:";
    private static final String USED_SEGMENT = "rt:used:";
    private static final String FAMILY_SEGMENT = "rt:fam:";
    private static final String USER_SEGMENT = ":rt:user:";
    private static final String FIELD_SEPARATOR = "|";
    private static final int FIELD_COUNT = 4;

    private final StringRedisTemplate redis;

    @Override
    public void save(String tokenHash, RefreshRecord record, Duration ttl) {
        try {
            redis.opsForValue().set(tokenKey(tokenHash), serialize(record), ttl);

            String familyKey = familyKey(record.familyId());
            redis.opsForSet().add(familyKey, tokenHash);
            redis.expire(familyKey, ttl);

            String userKey = userKey(record.tenantId(), record.userId());
            redis.opsForSet().add(userKey, record.familyId());
            redis.expire(userKey, ttl);

            // 会话 → 族的索引：注销会话时靠它找到要吊销的刷新令牌族（见 revokeBySession）
            if (record.sessionId() != null && !record.sessionId().isBlank()) {
                String sessionKey = sessionIndexKey(record.tenantId(), record.sessionId());
                redis.opsForValue().set(sessionKey, record.familyId(), ttl);
            }
        } catch (RuntimeException ex) {
            log.error("保存刷新令牌失败（用户将无法续期，需重新登录）。userId={}", record.userId(), ex);
        }
    }

    @Override
    public Optional<RefreshRecord> find(String tokenHash) {
        String raw;
        try {
            raw = redis.opsForValue().get(tokenKey(tokenHash));
        } catch (RuntimeException ex) {
            throw new AuthStoreUnavailableException("读取刷新令牌失败", ex);
        }
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(deserialize(raw));
    }

    @Override
    public void markRotated(String tokenHash, Duration ttl) {
        try {
            redis.opsForValue().set(usedKey(tokenHash), "1", ttl);
        } catch (RuntimeException ex) {
            log.error("标记刷新令牌已轮换失败（重放检测能力下降，需人工关注）hash={}", tokenHash, ex);
        }
    }

    @Override
    public boolean isRotated(String tokenHash) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(usedKey(tokenHash)));
        } catch (RuntimeException ex) {
            // 读不到标记时返回 false：此时调用方本就拿不到有效令牌记录（find 会抛或为空），
            // 因此不会因为这里返回 false 而放行任何东西
            log.warn("读取刷新令牌重放标记失败 hash={}", tokenHash, ex);
            return false;
        }
    }

    @Override
    public int revokeFamily(String familyId) {
        try {
            Set<String> tokens = redis.opsForSet().members(familyKey(familyId));
            int removed = 0;
            if (tokens != null && !tokens.isEmpty()) {
                for (String hash : tokens) {
                    redis.delete(List.of(tokenKey(hash), usedKey(hash)));
                    removed++;
                }
            }
            redis.delete(familyKey(familyId));
            if (removed > 0) {
                log.warn("已吊销刷新令牌族（重放检测或强制下线触发）familyId={} 令牌数={}", familyId, removed);
            }
            return removed;
        } catch (RuntimeException ex) {
            log.error("吊销刷新令牌族失败，需人工关注。familyId={}", familyId, ex);
            return 0;
        }
    }

    @Override
    public int revokeAllOfUser(long tenantId, long userId) {
        try {
            Set<String> families = redis.opsForSet().members(userKey(tenantId, userId));
            int removed = 0;
            if (families != null) {
                for (String familyId : families) {
                    removed += revokeFamily(familyId);
                }
            }
            redis.delete(userKey(tenantId, userId));
            if (removed > 0) {
                log.info("已吊销用户全部刷新令牌 tenantId={} userId={} 令牌数={}", tenantId, userId, removed);
            }
            return removed;
        } catch (RuntimeException ex) {
            log.error("吊销用户全部刷新令牌失败，需人工关注。tenantId={} userId={}", tenantId, userId, ex);
            return 0;
        }
    }

    @Override
    public int revokeBySession(long tenantId, long userId, String sessionId) {
        try {
            String familyId = redis.opsForValue().get(sessionIndexKey(tenantId, sessionId));
            if (familyId == null || familyId.isBlank()) {
                return 0;
            }
            int removed = revokeFamily(familyId);
            redis.delete(sessionIndexKey(tenantId, sessionId));
            return removed;
        } catch (RuntimeException ex) {
            log.error("按会话吊销刷新令牌失败（该会话的刷新令牌在到期前仍可换新令牌，需人工关注）。"
                    + "tenantId={} userId={} sessionId={}", tenantId, userId, sessionId, ex);
            return 0;
        }
    }

    // ------------------------------------------------------------------

    private static String tokenKey(String tokenHash) {
        return PREFIX + TOKEN_SEGMENT + tokenHash;
    }

    private static String usedKey(String tokenHash) {
        return PREFIX + USED_SEGMENT + tokenHash;
    }

    private static String familyKey(String familyId) {
        return PREFIX + FAMILY_SEGMENT + familyId;
    }

    private static String userKey(long tenantId, long userId) {
        return PREFIX + tenantId + USER_SEGMENT + userId;
    }

    private static String sessionIndexKey(long tenantId, String sessionId) {
        return PREFIX + tenantId + ":rt:sess:" + sessionId;
    }

    private static String serialize(RefreshRecord record) {
        return record.familyId()
                + FIELD_SEPARATOR + record.tenantId()
                + FIELD_SEPARATOR + record.userId()
                + FIELD_SEPARATOR + (record.sessionId() == null ? "" : record.sessionId());
    }

    private static RefreshRecord deserialize(String raw) {
        String[] parts = raw.split("\\" + FIELD_SEPARATOR, -1);
        if (parts.length < FIELD_COUNT) {
            throw new AuthStoreUnavailableException("刷新令牌记录格式异常：" + raw, null);
        }
        try {
            return new RefreshRecord(parts[0], Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]), parts[3]);
        } catch (NumberFormatException ex) {
            throw new AuthStoreUnavailableException("刷新令牌记录中的租户/用户 ID 非法：" + raw, ex);
        }
    }
}
