package com.webadmin.infrastructure.cache;

import com.webadmin.application.iam.port.TokenVersionPort;
import com.webadmin.infrastructure.persistence.mapper.UserMapper;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 令牌版本号读取（Redis 缓存 + 数据库回源 + 进程内兜底）。
 *
 * <h3>三层取值，各有一处不可替代</h3>
 * <ol>
 *   <li><b>Redis</b>：正常路径。鉴权是每请求热路径，必须避免查库</li>
 *   <li><b>数据库</b>：Redis 未命中或不可用时回源。这是<b>吊销能力的持久依据</b> ——
 *       Redis 清库后，"被停用账号的旧令牌"依然会被这里挡住</li>
 *   <li><b>进程内短 TTL 缓存</b>（{@link #LOCAL_TTL}）：只在 Redis 不可用时生效。
 *       它不是"为了快"，而是为了防止<b>Redis 挂掉时每个请求都去查库</b> ——
 *       鉴权路径打满数据库连接池会让整个系统不可用，那比"鉴权慢一点"严重得多</li>
 * </ol>
 *
 * <h3>为什么"查不到用户"返回 -1 而不是 0</h3>
 * 0 是合法版本号（新用户的默认值）。若把"用户已删除"也返回 0，
 * 那么一个已被删除的用户，其令牌（版本 0）会与"当前版本 0"匹配而<b>继续通过校验</b>，
 * 直到令牌自然过期。返回 -1 让调用方能把两者区分开，并对 -1 一律拒绝。
 *
 * <h3>缓存结构</h3>
 * <pre>{@code webadmin:{tenantId}:tokenver:{userId} → 版本号（字符串）}</pre>
 * TTL 10 分钟只是兜底：正常失效由 {@link #evict} 精确完成
 * （改密/停用/强退后立刻清）。<b>不依赖 TTL 保证正确性。</b>
 */
@Slf4j
@Component
public class RedisTokenVersionAdapter implements TokenVersionPort {

    private static final String KEY_PREFIX = "webadmin:";
    private static final String KEY_SUFFIX = ":tokenver:";
    private static final Duration REDIS_TTL = Duration.ofMinutes(10);

    /** 进程内兜底缓存时长：Redis 不可用期间，同一用户最多每 5 秒查一次库。 */
    private static final long LOCAL_TTL_MILLIS = 5_000L;

    /** 缓存条目上限，防止"用户数极多 + 长期 Redis 故障"时无界增长。 */
    private static final int LOCAL_MAX_ENTRIES = 10_000;

    private final StringRedisTemplate redis;
    private final UserMapper userMapper;

    /**
     * 进程内缓存。值为 {@code 版本号} + 过期时刻（打包成数组，避免再建一个类）。
     */
    private final Map<String, long[]> localCache = new ConcurrentHashMap<>();

    /**
     * {@code UserMapper} 惰性注入：本类会被 {@code JwtAuthenticationFilter} 使用，
     * 而过滤器在安全链构建期就被实例化 —— 此时 {@code sqlSessionFactory} 可能尚未就绪。
     * 与 {@code DeptHierarchyLookup} 是同一类环，处理方式保持一致。
     */
    public RedisTokenVersionAdapter(StringRedisTemplate redis, @Lazy UserMapper userMapper) {
        this.redis = redis;
        this.userMapper = userMapper;
    }

    @Override
    public long current(long tenantId, long userId) {
        String key = key(tenantId, userId);
        try {
            String raw = redis.opsForValue().get(key);
            if (raw != null && !raw.isBlank()) {
                return parseVersion(raw);
            }
        } catch (RuntimeException ex) {
            // Redis 故障：不是"放行"，而是回源数据库（见类注释第 2、3 层）
            log.warn("读取令牌版本号缓存失败，回源数据库。tenantId={} userId={}", tenantId, userId, ex);
            return fromLocalOrDb(tenantId, userId, key);
        }

        long version = loadFromDb(userId);
        if (version >= 0) {
            try {
                redis.opsForValue().set(key, String.valueOf(version), REDIS_TTL);
            } catch (RuntimeException ex) {
                log.debug("写入令牌版本号缓存失败（下次仍会回源）", ex);
            }
        }
        return version;
    }

    @Override
    public void evict(long tenantId, long userId) {
        String key = key(tenantId, userId);
        localCache.remove(key);
        try {
            redis.delete(key);
        } catch (RuntimeException ex) {
            // 失效失败的影响是"最长 5 秒内仍读旧版本号"（进程内兜底也会过期），
            // 因此不升级为 error；但必须记录，因为持续失败意味着吊销不生效
            log.warn("失效令牌版本号缓存失败。tenantId={} userId={}", tenantId, userId, ex);
        }
    }

    @Override
    public long bumpAllForTenant(long tenantId) {
        // 允许抛异常（与单用户路径的 fail-soft 相反）：调用方是可重试的事件处理器，
        // 静默失败等于"整个租户的强制下线没有发生"，那比抛出严重得多
        long affected = userMapper.bumpTokenVersionsForTenant(tenantId);
        evictTenant(tenantId);
        log.info("已提升租户全部用户令牌版本号 tenantId={} 用户数={}", tenantId, affected);
        return affected;
    }

    @Override
    public void evictTenant(long tenantId) {
        String keyPrefix = key(tenantId, 0);
        // 去掉末尾的用户段得到前缀
        String pattern = keyPrefix.substring(0, keyPrefix.length() - "0".length()) + "*";
        try (var cursor = redis.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(pattern).count(500).build())) {
            java.util.List<String> keys = new java.util.ArrayList<>();
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
            if (!keys.isEmpty()) {
                redis.delete(keys);
            }
        } catch (RuntimeException ex) {
            // SCAN/删除失败：残留缓存会在 TTL（10 分钟）后过期 —— 有界降级，记录即可
            log.warn("清理租户令牌版本号缓存失败（将在 TTL 后自然过期）。tenantId={}", tenantId, ex);
        }
        // 进程内兜底缓存同样要清，否则 Redis 故障期间本机仍读旧版本号
        localCache.keySet().removeIf(k -> k.startsWith(pattern.substring(0, pattern.length() - 1)));
    }

    // ------------------------------------------------------------------

    /**
     * Redis 不可用时的取值：先看进程内缓存，没有再查库并写入进程内缓存。
     *
     * <p>注意这里<b>不写 Redis</b>（它正不可用），只写进程内 —— 恢复后
     * 下一次正常路径会自然回填 Redis。
     */
    private long fromLocalOrDb(long tenantId, long userId, String key) {
        long now = System.currentTimeMillis();
        long[] cached = localCache.get(key);
        if (cached != null && cached[1] > now) {
            return cached[0];
        }
        long version = loadFromDb(userId);
        if (localCache.size() < LOCAL_MAX_ENTRIES) {
            localCache.put(key, new long[] {version, now + LOCAL_TTL_MILLIS});
        } else {
            // 超限时不再写入（退化为每次查库）：宁可慢，也不让内存无界增长
            log.warn("令牌版本号进程内缓存已达上限 {}，本次不再缓存", LOCAL_MAX_ENTRIES);
        }
        return version;
    }

    /** 回源数据库；用户不存在（或已逻辑删除）返回 -1。 */
    private long loadFromDb(long userId) {
        Long version = userMapper.selectTokenVersion(userId);
        return version == null ? -1L : version;
    }

    private static String key(long tenantId, long userId) {
        return KEY_PREFIX + tenantId + KEY_SUFFIX + userId;
    }

    private static long parseVersion(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            // 缓存内容损坏：按"查不到"处理（-1 → 拒绝），
            // 这比"当作 0"安全 —— 后者会让吊销失效
            log.warn("令牌版本号缓存内容非法，按拒绝处理：{}", raw);
            return -1L;
        }
    }
}
