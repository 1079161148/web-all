package com.webadmin.domain.iam.repository;

import com.webadmin.domain.iam.model.role.Role;
import com.webadmin.domain.iam.model.role.RoleKey;
import com.webadmin.domain.shared.RoleId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 角色仓储（领域层定义的端口）。
 */
public interface RoleRepository {

    Optional<Role> findById(RoleId id);

    Optional<Role> findByRoleKey(RoleKey roleKey);

    boolean existsByRoleKey(RoleKey roleKey);

    /**
     * 批量按 ID 加载。
     *
     * <p>这个方法被 {@code PermissionService} 在<b>每次鉴权</b>时调用（缓存未命中时）。
     * 因此实现必须用一条 {@code IN} 查询，绝不能写成循环单查 —— 一个用户挂 5 个角色
     * 就会变成 5 次数据库往返，而鉴权是每个请求都要走的热路径。
     */
    List<Role> findAllByIds(Collection<RoleId> ids);

    /** 查询某用户的所有角色。 */
    List<Role> findByUserId(long userId);

    /** 租户下所有可用角色 ID（用于权限变更时反查受影响用户）。 */
    Set<RoleId> findUsableRoleIds();

    void save(Role role);

    void delete(RoleId id);
}
