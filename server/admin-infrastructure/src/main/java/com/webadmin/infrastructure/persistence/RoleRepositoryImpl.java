package com.webadmin.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.domain.iam.model.role.Role;
import com.webadmin.domain.iam.model.role.RoleKey;
import com.webadmin.domain.iam.repository.RoleRepository;
import com.webadmin.domain.shared.DeptId;
import com.webadmin.domain.shared.MenuId;
import com.webadmin.domain.shared.RoleId;
import com.webadmin.infrastructure.persistence.converter.RoleConverter;
import com.webadmin.infrastructure.persistence.mapper.RoleDeptMapper;
import com.webadmin.infrastructure.persistence.mapper.RoleMapper;
import com.webadmin.infrastructure.persistence.mapper.RoleMenuMapper;
import com.webadmin.infrastructure.persistence.mapper.UserRoleMapper;
import com.webadmin.infrastructure.persistence.po.RoleDeptPO;
import com.webadmin.infrastructure.persistence.po.RoleMenuPO;
import com.webadmin.infrastructure.persistence.po.RolePO;
import com.webadmin.infrastructure.persistence.po.UserRolePO;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

/** 角色仓储实现。 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RoleRepositoryImpl implements RoleRepository {

    private final RoleMapper roleMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final RoleDeptMapper roleDeptMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleConverter roleConverter;
    private final Clock clock;

    @Override
    public Optional<Role> findById(RoleId id) {
        RolePO po = roleMapper.selectById(id.value());
        return assemble(po);
    }

    @Override
    public Optional<Role> findByRoleKey(RoleKey roleKey) {
        RolePO po = roleMapper.selectOne(new LambdaQueryWrapper<RolePO>()
                .eq(RolePO::getRoleKey, roleKey.value())
                .last("LIMIT 1"));
        return assemble(po);
    }

    @Override
    public boolean existsByRoleKey(RoleKey roleKey) {
        return roleMapper.exists(new LambdaQueryWrapper<RolePO>()
                .eq(RolePO::getRoleKey, roleKey.value()));
    }

    @Override
    public List<Role> findAllByIds(Collection<RoleId> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<Long> idList = ids.stream().map(RoleId::value).toList();
        List<RolePO> pos = roleMapper.selectByIds(idList);
        if (pos.isEmpty()) {
            return List.of();
        }
        // 批量装配菜单与部门：两次 IN 查询搞定，绝不逐个角色查（鉴权热路径）
        Map<Long, Set<Long>> menus = loadMenuIdsGrouped(idList);
        Map<Long, Set<Long>> depts = loadDeptIdsGrouped(idList);
        return pos.stream()
                .map(po -> roleConverter.toDomain(po,
                        menus.getOrDefault(po.getId(), Set.of()),
                        depts.getOrDefault(po.getId(), Set.of()),
                        clock))
                .toList();
    }

    /**
     * 查询某用户的角色（含菜单与部门装配）。
     *
     * <p>这是 <b>每次鉴权缓存未命中时</b> 都会走的路径，因此刻意压成 3 条 SQL：
     * ① 用户-角色关联 → ② 角色主体 → ③ 角色-菜单 / 角色-部门。
     * 对比"遍历角色逐个查菜单"的实现，在拥有 10 个角色的用户上能省下约 20 次往返。
     */
    @Override
    public List<Role> findByUserId(long userId) {
        List<UserRolePO> relations = userRoleMapper.selectList(
                new LambdaQueryWrapper<UserRolePO>().eq(UserRolePO::getUserId, userId));
        if (relations.isEmpty()) {
            return List.of();
        }
        Set<RoleId> roleIds = relations.stream()
                .map(r -> RoleId.of(r.getRoleId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return findAllByIds(roleIds);
    }

    @Override
    public Set<RoleId> findUsableRoleIds() {
        return roleMapper.selectList(new LambdaQueryWrapper<RolePO>()
                        .eq(RolePO::getStatus, "ACTIVE")
                        .select(RolePO::getId))
                .stream()
                .map(po -> RoleId.of(po.getId()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    public void save(Role role) {
        RolePO existing = roleMapper.selectById(role.id().value());
        if (existing == null) {
            RolePO po = roleConverter.toPO(role);
            roleMapper.insert(po);
        } else {
            roleConverter.mergeIntoPO(role, existing);
            int affected = roleMapper.updateById(existing);
            if (affected == 0) {
                log.warn("角色并发修改冲突: id={}", role.id().value());
                throw new BizException(CommonErrorCode.CONCURRENT_MODIFICATION,
                        "角色信息已被他人修改，请刷新后重试");
            }
        }
        syncPermissions(role);
    }

    @Override
    public void delete(RoleId id) {
        // 先校验是否仍有用户在用：角色被删而用户还挂着它，
        // 会导致该用户"看着有角色但角色不存在"，鉴权时静默降权 —— 问题极难定位。
        Long userCount = userRoleMapper.selectCount(new LambdaQueryWrapper<UserRolePO>()
                .eq(UserRolePO::getRoleId, id.value()));
        if (userCount != null && userCount > 0) {
            throw new BizException(com.webadmin.domain.iam.IamErrorCode.ROLE_IN_USE,
                    "该角色仍被 " + userCount + " 个用户使用，请先解除关联");
        }
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenuPO>()
                .eq(RoleMenuPO::getRoleId, id.value()));
        roleDeptMapper.delete(new LambdaQueryWrapper<RoleDeptPO>()
                .eq(RoleDeptPO::getRoleId, id.value()));
        roleMapper.deleteById(id.value());
    }

    private Optional<Role> assemble(RolePO po) {
        if (po == null) {
            return Optional.empty();
        }
        List<Long> singleId = List.of(po.getId());
        return Optional.of(roleConverter.toDomain(po,
                loadMenuIdsGrouped(singleId).getOrDefault(po.getId(), Set.of()),
                loadDeptIdsGrouped(singleId).getOrDefault(po.getId(), Set.of()),
                clock));
    }

    private void syncPermissions(Role role) {
        roleMenuMapper.delete(new LambdaQueryWrapper<RoleMenuPO>()
                .eq(RoleMenuPO::getRoleId, role.id().value()));
        for (MenuId menuId : role.menuIds()) {
            RoleMenuPO relation = new RoleMenuPO();
            relation.setRoleId(role.id().value());
            relation.setMenuId(menuId.value());
            roleMenuMapper.insert(relation);
        }
        roleDeptMapper.delete(new LambdaQueryWrapper<RoleDeptPO>()
                .eq(RoleDeptPO::getRoleId, role.id().value()));
        for (DeptId deptId : role.deptIds()) {
            RoleDeptPO relation = new RoleDeptPO();
            relation.setRoleId(role.id().value());
            relation.setDeptId(deptId.value());
            roleDeptMapper.insert(relation);
        }
    }

    private Map<Long, Set<Long>> loadMenuIdsGrouped(List<Long> roleIds) {
        List<RoleMenuPO> relations = roleMenuMapper.selectList(
                new LambdaQueryWrapper<RoleMenuPO>().in(RoleMenuPO::getRoleId, roleIds));
        return relations.stream().collect(Collectors.groupingBy(
                RoleMenuPO::getRoleId,
                Collectors.mapping(RoleMenuPO::getMenuId,
                        Collectors.toCollection(LinkedHashSet::new))));
    }

    private Map<Long, Set<Long>> loadDeptIdsGrouped(List<Long> roleIds) {
        List<RoleDeptPO> relations = roleDeptMapper.selectList(
                new LambdaQueryWrapper<RoleDeptPO>().in(RoleDeptPO::getRoleId, roleIds));
        return relations.stream().collect(Collectors.groupingBy(
                RoleDeptPO::getRoleId,
                Collectors.mapping(RoleDeptPO::getDeptId,
                        Collectors.toCollection(LinkedHashSet::new))));
    }
}
