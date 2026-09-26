package com.webadmin.infrastructure.cache;

import com.webadmin.application.iam.port.DeptHierarchyCachePort;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 部门层级缓存的 Redis 实现（一个租户一个 key）。
 *
 * <h3>Key 设计</h3>
 * <pre>{@code webadmin:{tenantId}:dept:tree}</pre>
 * 单 key 的收益在端口注释里说明了；这里补充一点：<b>失效成本恒定</b>——
 * 无论租户下有多少部门、树有多深，失效都是一次 {@code DEL}。
 * 这正是"不要用 SCAN 模糊删"的前提。
 *
 * <h3>序列化格式</h3>
 * <pre>{@code 100:0;200:0,100;210:0,100,200}</pre>
 * 即 {@code 部门ID:ancestors} 以 {@code ;} 分隔、{@code :} 分隔键值。
 * 与 {@link RedisPermissionCacheAdapter} 同思路：数据结构足够简单，
 * 不引入 JSON 序列化器 —— 省一次对象映射，且 {@code redis-cli get} 直接可读。
 *
 * <p>分隔符安全性来自领域约束：{@code ancestors} 只由数字与逗号组成
 * （见 {@code DeptAppService#resolveAncestorsForChild} 的拼装方式），
 * 部门 ID 是数字，因此 {@code ;} 与 {@code :} 不会出现在值里。
 *
 * <h3>TTL 与失效的关系</h3>
 * TTL 只是兜底，正确性由部门写操作触发的<b>精确失效</b>保证
 * （{@code DeptAppService} 在提交后调用 {@link #evict}）。
 * 不依赖 TTL 保证正确性 —— 这也是 {@code PermissionCachePort} 的一贯原则。
 *
 * <h3>故障处理：全部降级，绝不抛出</h3>
 * 本类位于"每次 SQL 改写都会经过"的路径上。若 Redis 抖动时抛异常，
 * 后果是<b>所有带数据权限的列表接口 500</b> —— 一个纯性能组件让业务停摆。
 * 因此三个方法都是 fail-soft：读失败 → 视为未命中（回源到数据库）；
 * 写失败 → 记日志（下次仍会回源）；失效失败 → 记 error（需要人工关注，
 * 因为它意味着可能有一段时间用到旧的部门范围）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisDeptHierarchyCacheAdapter implements DeptHierarchyCachePort {

    private static final String KEY_PREFIX = "webadmin:";
    private static final String KEY_SUFFIX = ":dept:tree";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String ENTRY_SEPARATOR = ";";
    private static final String FIELD_SEPARATOR = ":";

    private final StringRedisTemplate redis;

    @Override
    public Optional<Map<Long, String>> get(long tenantId) {
        try {
            String raw = redis.opsForValue().get(key(tenantId));
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(deserialize(raw));
        } catch (RuntimeException ex) {
            // 降级为未命中 → 调用方回源数据库。绝不能把缓存故障升级成接口故障。
            log.warn("读取部门层级缓存失败，降级为回源。tenantId={}", tenantId, ex);
            return Optional.empty();
        }
    }

    @Override
    public void put(long tenantId, Map<Long, String> ancestorsById) {
        if (ancestorsById == null || ancestorsById.isEmpty()) {
            // 空树不写入：把它当成"未缓存"能避免"缓存了一个空结果"这种
            // 看不出异常、却让人一直看不到数据的形态
            return;
        }
        try {
            redis.opsForValue().set(key(tenantId), serialize(ancestorsById), TTL);
        } catch (RuntimeException ex) {
            log.warn("写入部门层级缓存失败。tenantId={} 部门数={}", tenantId, ancestorsById.size(), ex);
        }
    }

    @Override
    public void evict(long tenantId) {
        try {
            redis.delete(key(tenantId));
            log.debug("已失效部门层级缓存 tenantId={}", tenantId);
        } catch (RuntimeException ex) {
            // 升到 error：失效失败意味着可能持续用到旧的部门范围（数据权限偏宽或偏窄），
            // 属于需要人工关注的情况。但仍不抛出 —— 部门写事务已经成功，
            // 抛异常会让调用方误以为保存失败。
            log.error("失效部门层级缓存失败，需人工关注（数据权限可能短暂使用旧的部门结构）。tenantId={}",
                    tenantId, ex);
        }
    }

    private static String key(long tenantId) {
        return KEY_PREFIX + tenantId + KEY_SUFFIX;
    }

    private static String serialize(Map<Long, String> ancestorsById) {
        StringBuilder sb = new StringBuilder();
        ancestorsById.forEach((id, ancestors) -> {
            if (sb.length() > 0) {
                sb.append(ENTRY_SEPARATOR);
            }
            sb.append(id).append(FIELD_SEPARATOR).append(ancestors == null ? "" : ancestors);
        });
        return sb.toString();
    }

    private static Map<Long, String> deserialize(String raw) {
        Map<Long, String> result = new LinkedHashMap<>();
        for (String entry : raw.split(ENTRY_SEPARATOR)) {
            int idx = entry.indexOf(FIELD_SEPARATOR.charAt(0));
            if (idx <= 0) {
                // 结构不符（旧格式或数据损坏）：跳过该条而不是整体失败。
                // 跳过会让"某部门的子树展开不出来"，比抛异常导致的接口 500 更容易发现与定位。
                continue;
            }
            try {
                Long id = Long.valueOf(entry.substring(0, idx));
                result.put(id, entry.substring(idx + 1));
            } catch (NumberFormatException ex) {
                log.warn("部门层级缓存存在无法解析的条目，已跳过：{}", entry);
            }
        }
        return result;
    }
}
