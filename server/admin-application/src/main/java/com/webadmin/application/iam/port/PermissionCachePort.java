package com.webadmin.application.iam.port;

import java.util.Optional;
import java.util.Set;

/**
 * 权限缓存端口。
 *
 * <h3>Key 必须带租户前缀</h3>
 * 格式固定为 {@code {app}:{tenantId}:perm:{userId}}。
 * 这不是"建议"而是硬约束：多租户系统里最容易出的一类事故就是
 * 缓存 Key 未带租户，导致 A 租户的权限被 B 租户命中 ——
 * 而且它<b>不会报错</b>，只会让某些人莫名多出或少掉权限。
 *
 * <p>因此本端口把 tenantId 作为<b>必填参数</b>而不是让调用方自己拼字符串 ——
 * 让遗漏租户在类型层面就写不出来。
 *
 * <h3>缓存不参与正确性</h3>
 * 设计文档 §9.5 原则：任何"依赖缓存一定命中"的逻辑都是 Bug。
 * 因此 {@link #get} 返回 {@code Optional}，未命中时调用方必须能自己算出来；
 * 缓存只是把"每次鉴权查两次库"降为"大部分时候零次查询"。
 */
public interface PermissionCachePort {

    /** 读取缓存的权限集合；未命中返回空。 */
    Optional<CachedPermissions> get(long tenantId, long userId);

    /** 写入权限缓存。 */
    void put(long tenantId, long userId, CachedPermissions permissions);

    /** 精确失效单个用户。 */
    void evict(long tenantId, long userId);

    /** 按角色失效（角色权限变更时使用，需调用方先查出受影响的用户 ID）。 */
    void evictUsers(long tenantId, Set<Long> userIds);

    /**
     * 缓存的权限快照。
     *
     * <h3>为什么把数据范围也放进来</h3>
     * 数据权限（{@code @DataScope}）的作用点是 <b>SQL 改写</b>，发生在每条查询上。
     * 若在改写时再去查"当前用户的角色数据范围 / 所属部门 / 自定义部门集合"，
     * 就等于<b>每条 SQL 之前先跑 3 条 SQL</b>。而这些信息与权限码来自同一批角色数据，
     * 天然适合一起缓存 —— 一次解析、30 分钟有效、变更时精确失效。
     *
     * <p>这是"把相关的读放大问题一次性解决"的思路：不是给数据权限单独做一套缓存，
     * 而是让已有的权限缓存顺带承担它。少一套缓存就少一处不一致的可能。
     *
     * @param permissionCodes 权限码集合（如 {@code iam:user:add}）
     * @param roleKeys        角色标识集合（如 {@code SUPER_ADMIN}）
     * @param superAdmin      是否超级管理员。
     *                        <b>单独存一个布尔而不是靠"权限集合是否包含全部权限"来判断</b> ——
     *                        后者在新增权限点后会失效（超管反而少了一项新权限），
     *                        而"是不是超管"是身份属性，不会随时间漂移
     * @param widestDataScope 多角色取并集后的最宽数据范围。<b>没有任何角色时为
     *                        {@code SELF}</b>（fail-closed），而不是 {@code ALL}
     * @param deptId          用户所属部门；可能为 null（未分配部门的新用户）
     * @param customDeptIds   {@code CUSTOM} 范围下可见的部门集合（各角色并集）
     */
    record CachedPermissions(
            Set<String> permissionCodes,
            Set<String> roleKeys,
            boolean superAdmin,
            com.webadmin.domain.iam.model.role.DataScope widestDataScope,
            Long deptId,
            Set<Long> customDeptIds
    ) {

        /**
         * 兼容构造：只给权限与角色，数据范围收敛到最窄。
         *
         * <p>刻意把数据范围默认为 {@link com.webadmin.domain.iam.model.role.DataScope#SELF}
         * 而不是 {@code ALL} —— 任何"忘了传数据范围"的调用点都会得到最小可见范围，
         * 表现为功能受限（可发现、可修复），而不是静默放开（不可发现）。
         */
        public static CachedPermissions of(Set<String> permissionCodes,
                                           Set<String> roleKeys,
                                           boolean superAdmin) {
            return new CachedPermissions(
                    Set.copyOf(permissionCodes), Set.copyOf(roleKeys), superAdmin,
                    com.webadmin.domain.iam.model.role.DataScope.SELF, null, Set.of());
        }

        public static CachedPermissions of(Set<String> permissionCodes,
                                           Set<String> roleKeys,
                                           boolean superAdmin,
                                           com.webadmin.domain.iam.model.role.DataScope widestDataScope,
                                           Long deptId,
                                           Set<Long> customDeptIds) {
            return new CachedPermissions(
                    Set.copyOf(permissionCodes),
                    Set.copyOf(roleKeys),
                    superAdmin,
                    widestDataScope == null
                            ? com.webadmin.domain.iam.model.role.DataScope.SELF
                            : widestDataScope,
                    deptId,
                    customDeptIds == null ? Set.of() : Set.copyOf(customDeptIds));
        }

        public boolean has(String permissionCode) {
            if (superAdmin) {
                return true;
            }
            return permissionCodes.contains(permissionCode);
        }

        /**
         * 供<b>前端</b>使用的有效权限码集合。
         *
         * <p>超管返回通配符 {@code "*"} 而不是逐条列出全部权限码。原因：
         * <ul>
         *   <li>逐条列出意味着每次新增权限点都要同步超管列表 —— 一旦漏了，
         *       超管会"看不到新功能的按钮"，而这是一个很难联想到权限配置的问题</li>
         *   <li>通配符让"超管拥有一切"成为语义上的事实，而不是一份需要维护的快照</li>
         * </ul>
         * 前端判定规则固定为：{@code perms.includes('*') || perms.includes(code)}。
         *
         * <p>⚠️ 这<b>只是前端渲染依据</b>。服务端判定走 {@link #has(String)}，
         * 两者同源但服务端不依赖前端传来的任何数据。
         */
        public java.util.Set<String> effectivePermissionCodes() {
            return superAdmin ? java.util.Set.of("*") : permissionCodes;
        }
    }
}
