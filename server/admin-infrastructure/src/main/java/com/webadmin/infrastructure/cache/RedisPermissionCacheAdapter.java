package com.webadmin.infrastructure.cache;

import com.webadmin.application.iam.port.PermissionCachePort;
import com.webadmin.application.iam.port.PermissionCachePort.CachedPermissions;
import com.webadmin.domain.iam.model.role.DataScope;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 权限缓存的 Redis 实现。
 *
 * <h3>Key 设计</h3>
 * <pre>{@code webadmin:{tenantId}:perm:{userId}}</pre>
 * 租户段是<b>强制</b>的（端口签名里就要求 tenantId），因为"忘带租户前缀"
 * 是多租户项目最经典的一类事故：它不报错，只是让某些人莫名多出或少掉权限。
 *
 * <h3>序列化为什么不用 JSON</h3>
 * 权限缓存的数据结构极简单（一个布尔 + 两个字符串集合），
 * 用 {@code |} 与 {@code ,} 分隔即可，无需引入 JSON 序列化器配置。
 * 这带来两个实际好处：<b>省掉一次对象映射开销</b>（鉴权是每请求热路径），
 * 以及<b>人肉排查时 {@code redis-cli get} 直接可读</b>。
 *
 * <p>代价是键值里不能出现分隔符。权限码与角色标识的字符集被
 * {@code RoleKey} / 权限码规范限制在 {@code [A-Za-z0-9_:.-]}，不含分隔符，
 * 因此这个前提是有保障的 —— <b>利用领域层的字符集约束来简化基础设施实现</b>，
 * 而不是在基础设施里做防御性转义。
 *
 * <h3>TTL 与"缓存不参与正确性"</h3>
 * 30 分钟 TTL 只是兜底：正常失效由角色/用户变更事件驱动（精确失效）。
 * 把 TTL 设长一点是安全的，因为失效是主动的；而设短一点也无害，
 * 只是回源次数变多。<b>不要依赖 TTL 来保证正确性。</b>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisPermissionCacheAdapter implements PermissionCachePort {

    private static final String KEY_PREFIX = "webadmin:";
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final String FIELD_SEPARATOR = "\\|";
    private static final String ITEM_SEPARATOR = ",";

    /**
     * 反序列化要求的最低段数（v2 格式共 6 段）。
     *
     * <p>用常量而不是字面量：将来加字段时，改这里一处即可，
     * 不会出现"序列化写了 7 段、反序列化还只要求 6 段"这种静默错位。
     */
    private static final int REQUIRED_SEGMENTS = 6;

    private final StringRedisTemplate redis;

    @Override
    public Optional<CachedPermissions> get(long tenantId, long userId) {
        try {
            String raw = redis.opsForValue().get(key(tenantId, userId));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(deserialize(raw));
        } catch (RuntimeException ex) {
            // 缓存故障必须降级为"未命中"，由调用方回源。
            // 若在这里抛异常，Redis 抖动会直接变成"整个系统无法鉴权"——
            // 一个纯性能组件不该有能力让业务停摆。
            log.warn("读取权限缓存失败，降级为回源。tenantId={} userId={}", tenantId, userId, ex);
            return Optional.empty();
        }
    }

    @Override
    public void put(long tenantId, long userId, CachedPermissions permissions) {
        try {
            redis.opsForValue().set(key(tenantId, userId), serialize(permissions), TTL);
        } catch (RuntimeException ex) {
            // 写失败不影响正确性（下次仍会回源），因此只记录不抛出
            log.warn("写入权限缓存失败。tenantId={} userId={}", tenantId, userId, ex);
        }
    }

    @Override
    public void evict(long tenantId, long userId) {
        try {
            redis.delete(key(tenantId, userId));
        } catch (RuntimeException ex) {
            log.warn("失效权限缓存失败。tenantId={} userId={}", tenantId, userId, ex);
        }
    }

    @Override
    public void evictUsers(long tenantId, Set<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        try {
            var keys = userIds.stream().map(id -> key(tenantId, id)).toList();
            redis.delete(keys);
            log.info("按角色变更批量失效权限缓存 tenantId={} 用户数={}", tenantId, userIds.size());
        } catch (RuntimeException ex) {
            // 批量失效失败的影响比单条严重：可能让一批用户保留旧的（可能是放宽后的）权限。
            // 因此这里升到 error 级别，便于告警关注 —— 但仍不抛出，
            // 因为角色变更事务本身已经提交成功，抛异常会误导调用方认为保存失败。
            log.error("批量失效权限缓存失败，需人工关注。tenantId={} 用户数={}",
                    tenantId, userIds.size(), ex);
        }
    }

    private static String key(long tenantId, long userId) {
        return KEY_PREFIX + tenantId + ":perm:" + userId;
    }

    /**
     * 序列化格式（v2）：
     * <pre>{@code superAdmin|roles|perms|dataScope|deptId|customDeptIds}</pre>
     *
     * <p>往这个格式里加字段时，<b>只能追加在末尾，且必须同步提高
     * {@code deserialize} 的最低段数要求</b>。若在中间插入，
     * 线上残留的旧格式缓存会被解析成完全错位的值 ——
     * 比如把权限码当成数据范围，后果是权限判定静默出错。
     * 这类"缓存格式演进"问题不会在重启时暴露，只会表现为零星的权限异常。
     */
    private static String serialize(CachedPermissions permissions) {
        return (permissions.superAdmin() ? "1" : "0")
                + "|" + String.join(ITEM_SEPARATOR, permissions.roleKeys())
                + "|" + String.join(ITEM_SEPARATOR, permissions.permissionCodes())
                + "|" + (permissions.widestDataScope() == null
                        ? DataScope.SELF.name() : permissions.widestDataScope().name())
                + "|" + (permissions.deptId() == null ? "" : permissions.deptId())
                + "|" + permissions.customDeptIds().stream()
                        .map(String::valueOf)
                        .collect(java.util.stream.Collectors.joining(ITEM_SEPARATOR));
    }

    private static CachedPermissions deserialize(String raw) {
        String[] parts = raw.split(FIELD_SEPARATOR, -1);
        if (parts.length < REQUIRED_SEGMENTS) {
            // 结构不符（旧版本格式或数据损坏）。
            // ⚠️ 这里返回的必须是**最严格**的组合：非超管 + 空权限 + SELF 数据范围。
            // 返回空权限会导致"看不到按钮"（可发现、可修复），
            // 而若误判成超管或 ALL 范围，则是静默的越权 —— 两者危险程度完全不同。
            log.warn("权限缓存结构不符合当前格式（段数={}，期望≥{}），已按最严格权限处理并视为未命中。"
                            + "这通常发生在版本升级后，旧缓存会在 TTL 到期后自然消失",
                    parts.length, REQUIRED_SEGMENTS);
            return CachedPermissions.of(Set.of(), Set.of(), false);
        }
        boolean superAdmin = "1".equals(parts[0]);
        return CachedPermissions.of(
                splitToSet(parts[2]),
                splitToSet(parts[1]),
                superAdmin,
                parseDataScope(parts[3]),
                parseLongOrNull(parts[4]),
                splitToLongSet(parts[5]));
    }

    private static DataScope parseDataScope(String value) {
        if (value == null || value.isBlank()) {
            return DataScope.SELF;
        }
        try {
            return DataScope.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            // 无法识别的范围 → 收敛到最窄。fail-closed。
            return DataScope.SELF;
        }
    }

    private static Long parseLongOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Set<Long> splitToLongSet(String value) {
        if (value == null || value.isEmpty()) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>();
        for (String item : value.split(ITEM_SEPARATOR)) {
            Long parsed = parseLongOrNull(item);
            if (parsed != null) {
                result.add(parsed);
            }
        }
        return result;
    }

    private static Set<String> splitToSet(String value) {
        if (value == null || value.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String item : value.split(ITEM_SEPARATOR)) {
            if (!item.isBlank()) {
                result.add(item.trim());
            }
        }
        return result;
    }
}
