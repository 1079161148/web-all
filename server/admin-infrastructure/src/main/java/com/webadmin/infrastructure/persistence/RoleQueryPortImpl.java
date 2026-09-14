package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webadmin.application.iam.dto.RoleDTO;
import com.webadmin.application.iam.port.RoleQueryPort;
import com.webadmin.application.iam.query.RolePageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.infrastructure.persistence.mapper.RoleDeptMapper;
import com.webadmin.infrastructure.persistence.mapper.RoleMapper;
import com.webadmin.infrastructure.persistence.mapper.RoleMenuMapper;
import com.webadmin.infrastructure.persistence.mapper.UserRoleMapper;
import com.webadmin.infrastructure.persistence.po.RoleDeptPO;
import com.webadmin.infrastructure.persistence.po.RoleMenuPO;
import com.webadmin.infrastructure.persistence.po.RolePO;
import com.webadmin.infrastructure.persistence.po.UserRolePO;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 角色读侧实现。
 *
 * <h3>userCount 的统计方式</h3>
 * 用<b>一次分组统计</b>取当页所有角色的用户数，而不是逐角色 {@code count(*)}。
 * 后者在 20 条一页时就是 20 次查询 —— 典型的 N+1，
 * 而它偏偏发生在"打开角色管理"这种高频操作上。
 */
@Repository
@RequiredArgsConstructor
public class RoleQueryPortImpl implements RoleQueryPort {

    private final RoleMapper roleMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final RoleDeptMapper roleDeptMapper;
    private final UserRoleMapper userRoleMapper;

    @Override
    public PageResult<RoleDTO> page(RolePageQuery query) {
        LambdaQueryWrapper<RolePO> wrapper = new LambdaQueryWrapper<RolePO>()
                .like(query.getRoleName() != null && !query.getRoleName().isBlank(),
                        RolePO::getRoleName, query.getRoleName())
                .like(query.getRoleKey() != null && !query.getRoleKey().isBlank(),
                        RolePO::getRoleKey, query.getRoleKey())
                .eq(query.getStatus() != null && !query.getStatus().isBlank(),
                        RolePO::getStatus, query.getStatus())
                .orderByAsc(RolePO::getSort)
                .orderByAsc(RolePO::getId);

        Page<RolePO> page = roleMapper.selectPage(
                new Page<>(query.getPage(), query.getSize()), wrapper);

        // 列表页不返回 menuIds / deptIds（那两个集合只用于权限分配界面回显），
        // 但 userCount 与 menuCount 要算 —— 它们让管理员一眼看出角色是否在用、是否已配置
        List<Long> roleIds = page.getRecords().stream().map(RolePO::getId).toList();
        Map<Long, Integer> userCounts = countByRole(userRoleMapper, roleIds);
        Map<Long, Integer> menuCounts = countByRole(roleMenuMapper, roleIds);

        List<RoleDTO> records = page.getRecords().stream()
                .map(po -> toDTO(po, null, null,
                        userCounts.getOrDefault(po.getId(), 0),
                        menuCounts.getOrDefault(po.getId(), 0)))
                .toList();

        return PageResult.of(records, page.getTotal(),
                (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    public Optional<RoleDTO> findById(long roleId) {
        RolePO po = roleMapper.selectById(roleId);
        if (po == null) {
            return Optional.empty();
        }
        List<Long> menuIds = roleMenuMapper.selectList(
                        new LambdaQueryWrapper<RoleMenuPO>().eq(RoleMenuPO::getRoleId, roleId))
                .stream().map(RoleMenuPO::getMenuId).toList();
        List<Long> deptIds = roleDeptMapper.selectList(
                        new LambdaQueryWrapper<RoleDeptPO>().eq(RoleDeptPO::getRoleId, roleId))
                .stream().map(RoleDeptPO::getDeptId).toList();
        return Optional.of(toDTO(po, menuIds, deptIds, null, menuIds.size()));
    }

    @Override
    public List<RoleDTO> findUsable() {
        return roleMapper.selectList(new LambdaQueryWrapper<RolePO>()
                        .eq(RolePO::getStatus, "ACTIVE")
                        .orderByAsc(RolePO::getSort))
                .stream()
                .map(po -> toDTO(po, null, null, null, null))
                .toList();
    }

    // ------------------------------------------------------------------

    /**
     * 按角色分组统计关联行数。
     *
     * <p>两个关联表（用户-角色、角色-菜单）的字段名不同，用两个小方法分别处理，
     * 而不是搞一个泛型的"万能统计" —— 后者要靠反射取字段名，代价远大于省下的几行代码。
     */
    private Map<Long, Integer> countByRole(UserRoleMapper mapper, List<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<UserRolePO>()
                        .select(UserRolePO::getRoleId)
                        .in(UserRolePO::getRoleId, roleIds))
                .stream()
                .collect(Collectors.groupingBy(UserRolePO::getRoleId,
                        Collectors.summingInt(row -> 1)));
    }

    private Map<Long, Integer> countByRole(RoleMenuMapper mapper, List<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        return mapper.selectList(new LambdaQueryWrapper<RoleMenuPO>()
                        .select(RoleMenuPO::getRoleId)
                        .in(RoleMenuPO::getRoleId, roleIds))
                .stream()
                .collect(Collectors.groupingBy(RoleMenuPO::getRoleId,
                        Collectors.summingInt(row -> 1)));
    }

    private RoleDTO toDTO(RolePO po, List<Long> menuIds, List<Long> deptIds,
                          Integer userCount, Integer menuCount) {
        return new RoleDTO(
                po.getId(),
                po.getTenantId(),
                po.getRoleName(),
                po.getRoleKey(),
                po.getSort(),
                po.getDataScope(),
                Boolean.TRUE.equals(po.getBuiltin()),
                po.getStatus(),
                po.getRemark(),
                userCount,
                menuCount,
                menuIds == null ? null : List.copyOf(new LinkedHashSet<>(menuIds)),
                deptIds == null ? null : List.copyOf(new LinkedHashSet<>(deptIds)),
                po.getCreateTime());
    }
}
