package com.webadmin.application.iam.port;

import java.util.Map;
import java.util.Optional;

/**
 * 部门层级结构缓存端口。
 *
 * <h3>为什么缓存的是"整棵树"而不是"每个部门的展开结果"</h3>
 * 数据权限的部门范围需要在 <b>每次 SQL 改写</b>时拿到"某部门及其后代"的 ID 集合。
 * 两种缓存粒度：
 * <ul>
 *   <li><b>按部门存展开结果</b>：key 数 = 部门数，每次命中只取一个集合。
 *       但一个租户的部门往往构成很深的树，key 数会随部门数线性增长，
 *       且<b>失效时要删一批 key</b>（在生产上只能用 SCAN 模糊删，是明确的反模式）</li>
 *   <li><b>按租户存整棵树的 ancestors 映射</b>（本接口采用）：
 *       <b>一个租户一个 key</b>。命中后"某部门及其后代"在内存里算（部门树规模是几百级，
 *       扫描代价可忽略）。失效只需删<b>一个</b> key —— 无需 SCAN，
 *       且天然对"移动部门导致整棵子树路径变化"这种情况是完整的</li>
 * </ul>
 *
 * <h3>为什么租户 ID 是显式参数而不是从 TenantContext 读</h3>
 * 与 {@link PermissionCachePort} 同理：端口层把租户写进签名，
 * 让"忘了带租户前缀"这件事<b>在编译期就无处遁形</b>。
 * key 里缺租户段的后果不是报错，而是让不同租户互相看到对方的部门范围。
 *
 * @see PermissionCachePort
 */
public interface DeptHierarchyCachePort {

    /**
     * 取某租户的部门层级映射：{@code 部门ID → ancestors}（不含被逻辑删除的部门）。
     *
     * @return 未缓存（或缓存不可用）时返回 {@link Optional#empty()}，由调用方回源
     */
    Optional<Map<Long, String>> get(long tenantId);

    /**
     * 写入某租户的部门层级映射。
     *
     * <p>实现必须"失败不抛"：缓存是纯性能组件，它不该有能力让业务停摆。
     */
    void put(long tenantId, Map<Long, String> ancestorsById);

    /**
     * 失效某租户的部门层级缓存。部门新增 / 修改 / 移动 / 删除后必须调用，
     * 且应在<b>事务提交之后</b>（见 {@code DeptAppService} 的说明）。
     */
    void evict(long tenantId);
}
