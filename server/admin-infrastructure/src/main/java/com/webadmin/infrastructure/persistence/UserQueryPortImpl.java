package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.webadmin.application.iam.dto.UserDTO;
import com.webadmin.application.iam.port.UserQueryPort;
import com.webadmin.application.iam.query.UserPageQuery;
import com.webadmin.common.api.PageResult;
import com.webadmin.infrastructure.persistence.mapper.DeptMapper;
import com.webadmin.infrastructure.persistence.mapper.RoleMapper;
import com.webadmin.infrastructure.persistence.mapper.UserMapper;
import com.webadmin.infrastructure.persistence.mapper.UserRoleMapper;
import com.webadmin.infrastructure.persistence.po.DeptPO;
import com.webadmin.infrastructure.persistence.po.RolePO;
import com.webadmin.infrastructure.persistence.po.UserPO;
import com.webadmin.infrastructure.persistence.po.UserRolePO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 用户读侧实现。
 *
 * <h3>角色信息的装配方式</h3>
 * 列表查询本身<b>不 JOIN 角色表</b>，而是拿到当页用户 ID 后
 * 再做两次批量查询（用户-角色关联、角色主体）后在内存组装。
 *
 * <p>对比"在列表 SQL 里 JOIN 角色并 GROUP_CONCAT"：
 * 后者会让分页的 count 语句变得复杂（JOIN 会放大行数，需要 DISTINCT 才准确），
 * 而 DISTINCT + GROUP_CONCAT 的 count 很容易算错页数。
 * <b>3 条简单查询换掉一个容易算错页数的 JOIN，是划算的。</b>
 * 当页最多几百行，两次批量查询的成本可以忽略。
 */
@Repository
@RequiredArgsConstructor
public class UserQueryPortImpl implements UserQueryPort {

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final DeptMapper deptMapper;

    @Override
    public PageResult<UserDTO> page(UserPageQuery query) {
        Page<UserPO> page = new Page<>(query.getPage(), query.getSize());
        // 分页与数据权限都由拦截器负责：这里只传条件，
        // 页码、总数、以及"本部门及以下"的过滤条件都不需要手写
        List<UserPO> rows = userMapper.selectUserPage(page, query);

        Map<Long, List<RoleBrief>> rolesByUser = loadRolesByUser(
                rows.stream().map(UserPO::getId).filter(java.util.Objects::nonNull).toList());

        List<UserDTO> records = rows.stream()
                .map(po -> toDTO(po, rolesByUser.getOrDefault(po.getId(), List.of())))
                .toList();

        return PageResult.of(records, page.getTotal(),
                (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    public Optional<UserDTO> findById(long userId) {
        UserPO po = userMapper.selectById(userId);
        if (po == null) {
            return Optional.empty();
        }
        // 详情页需要部门名，单独取一次（列表走 JOIN，详情不值得为一个字段再开一条 JOIN 查询）
        if (po.getDeptId() != null) {
            po.setDeptName(loadDeptName(po.getDeptId()));
        }
        return Optional.of(toDTO(po, loadRolesByUser(List.of(userId)).getOrDefault(userId, List.of())));
    }

    @Override
    public List<Long> findIdsByRoleId(long roleId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<UserRolePO>()
                        .select(UserRolePO::getUserId)
                        .eq(UserRolePO::getRoleId, roleId))
                .stream()
                .map(UserRolePO::getUserId)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    // ------------------------------------------------------------------

    /** 批量装载"用户 → 角色"，避免逐用户查询造成 N+1。 */
    private Map<Long, List<RoleBrief>> loadRolesByUser(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<UserRolePO> relations = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRolePO>().in(UserRolePO::getUserId, userIds));
        if (relations.isEmpty()) {
            return Map.of();
        }

        Set<Long> roleIds = relations.stream()
                .map(UserRolePO::getRoleId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, String> roleNames = roleMapper.selectByIds(roleIds).stream()
                .collect(Collectors.toMap(RolePO::getId, RolePO::getRoleName, (a, b) -> a));

        Map<Long, List<RoleBrief>> result = new LinkedHashMap<>();
        for (UserRolePO relation : relations) {
            Long userId = relation.getUserId();
            Long roleId = relation.getRoleId();
            if (userId == null || roleId == null) {
                continue;
            }
            result.computeIfAbsent(userId, key -> new java.util.ArrayList<>())
                    .add(new RoleBrief(roleId, roleNames.getOrDefault(roleId, "未知角色")));
        }
        return result;
    }

    private String loadDeptName(Long deptId) {
        DeptPO dept = deptMapper.selectById(deptId);
        return dept == null ? null : dept.getDeptName();
    }

    private UserDTO toDTO(UserPO po, List<RoleBrief> roles) {
        return new UserDTO(
                po.getId(),
                po.getTenantId(),
                po.getDeptId(),
                po.getDeptName(),
                po.getUsername(),
                po.getNickname(),
                po.getPhone(),
                po.getEmail(),
                po.getSex(),
                po.getAvatar(),
                po.getStatus() == null ? null : po.getStatus().name(),
                po.getLoginIp(),
                po.getLoginTime(),
                roles.stream().map(RoleBrief::id).toList(),
                roles.isEmpty() ? null
                        : roles.stream().map(RoleBrief::name).collect(Collectors.joining("、")),
                po.getCreateTime());
    }

    /** 角色摘要：列表只需要 ID 与名称。 */
    private record RoleBrief(Long id, String name) {
    }
}
