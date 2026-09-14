package com.webadmin.application.iam;

import com.webadmin.application.iam.security.PermissionResolver;
import com.webadmin.common.error.BizException;
import com.webadmin.common.tenant.TenantContext;
import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.iam.model.role.DataScope;
import com.webadmin.domain.iam.model.role.Role;
import com.webadmin.domain.iam.model.role.RoleKey;
import com.webadmin.domain.iam.repository.RoleRepository;
import com.webadmin.domain.shared.DeptId;
import com.webadmin.domain.shared.IdGenerator;
import com.webadmin.domain.shared.MenuId;
import com.webadmin.domain.shared.RoleId;
import com.webadmin.domain.shared.TenantId;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色应用服务（写侧）。
 *
 * <h3>角色权限变更的影响面最大，因此缓存失效必须做对</h3>
 * 改一个角色的权限，会影响<b>该角色下的所有用户</b>。若只清当前用户或不清，
 * 会出现"改完权限后部分人生效、部分人不生效"这种最难排查的状态。
 *
 * <p>本服务在权限变更后按 {@code roleId} 反查受影响的用户并<b>批量失效</b> ——
 * 这正是 {@code UserRepository.findIdsByRoleId} 存在的理由。
 * 这个反查在设计上没有替代方案：权限缓存的 key 是 {@code tenant:userId}，
 * 无法从 roleId 直接推导出所有受影响的 key。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleAppService {

    private final RoleRepository roleRepository;
    private final PermissionResolver permissionResolver;
    private final com.webadmin.domain.iam.repository.UserRepository userRepository;
    private final IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 创建角色命令。
     *
     * <p>作为嵌套类型放在服务内，因为它<b>只服务于这一个方法</b>。
     * 命令对象的价值在于"表达一次业务意图的完整输入"，
     * 一旦只有一个消费方，独立文件只会增加跳转成本。
     */
    public record CreateRoleCommand(String roleKey, String roleName, Integer sort,
                                    String dataScope, String remark) {
    }

    @Transactional
    public Long createRole(CreateRoleCommand command) {
        long tenantId = TenantContext.require();
        RoleKey roleKey = RoleKey.of(command.roleKey());

        if (roleRepository.existsByRoleKey(roleKey)) {
            throw new BizException(IamErrorCode.ROLE_CODE_DUPLICATED,
                    "角色标识「" + roleKey.value() + "」已存在");
        }

        Role role = Role.create(
                idGenerator.nextRoleId(),
                TenantId.ofPersisted(tenantId),
                roleKey,
                command.roleName(),
                command.sort() == null ? 0 : command.sort(),
                parseDataScope(command.dataScope()),
                clock);
        roleRepository.save(role);
        log.info("创建角色 tenantId={} roleId={} roleKey={}",
                tenantId, role.id().value(), roleKey.value());
        return role.id().value();
    }

    @Transactional
    public void updateRole(long roleId, String roleName, Integer sort) {
        Role role = loadRole(roleId);
        // 内置角色的不可变性由聚合裁决（rename 内部会拒绝），
        // 应用层不重复判断 —— 否则规则就有两处，迟早不一致
        role.rename(roleName, sort == null ? role.sort() : sort);
        roleRepository.save(role);
    }

    @Transactional
    public void deleteRole(long roleId) {
        Role role = loadRole(roleId);
        role.assertDeletable();

        // 仍有用户在使用时拒绝删除。仓储层也会再拦一次（双保险），
        // 因为"删掉一个还有人用的角色"会让那些用户静默降权 ——
        // 表现为"他突然什么都看不到了"，而原因藏在很久以前的一次删除操作里
        Set<com.webadmin.domain.shared.UserId> affected = userRepository.findIdsByRoleId(role.id());
        if (!affected.isEmpty()) {
            throw new BizException(IamErrorCode.ROLE_IN_USE,
                    "该角色仍被 " + affected.size() + " 个用户使用，请先解除关联");
        }

        role.markDeleted();
        roleRepository.save(role);
        log.info("删除角色 roleId={} roleKey={}", roleId, role.roleKey().value());
    }

    /**
     * 分配权限（菜单 + 数据范围 + 自定义部门），全量覆盖语义。
     */
    @Transactional
    public void assignPermissions(long roleId, Set<Long> menuIds,
                                  String dataScope, Set<Long> deptIds) {
        long tenantId = TenantContext.require();
        Role role = loadRole(roleId);

        role.assignPermissions(toMenuIds(menuIds), parseDataScope(dataScope), toDeptIds(deptIds));
        roleRepository.save(role);

        evictPermissionsOfRoleUsers(tenantId, roleId);
        log.info("分配角色权限 roleId={} 菜单数={} 数据范围={} 自定义部门数={}",
                roleId, menuIds == null ? 0 : menuIds.size(), dataScope,
                deptIds == null ? 0 : deptIds.size());
    }

    @Transactional
    public void changeStatus(long roleId, String status) {
        long tenantId = TenantContext.require();
        Role role = loadRole(roleId);
        role.changeStatus(status);
        roleRepository.save(role);
        // 停用角色会让该角色下的用户立刻降权，必须同步失效
        evictPermissionsOfRoleUsers(tenantId, roleId);
        log.info("变更角色状态 roleId={} → {}", roleId, status);
    }

    // ------------------------------------------------------------------

    private Role loadRole(long roleId) {
        return roleRepository.findById(RoleId.of(roleId))
                .orElseThrow(() -> new BizException(IamErrorCode.ROLE_NOT_FOUND,
                        "角色不存在或已被删除（ID=" + roleId + "）"));
    }

    /** 失效该角色下所有用户的权限缓存（角色变更的影响面是"所有关联用户"）。 */
    private void evictPermissionsOfRoleUsers(long tenantId, long roleId) {
        Set<Long> affectedUserIds = userRepository.findIdsByRoleId(RoleId.of(roleId)).stream()
                .map(com.webadmin.domain.shared.UserId::value)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (affectedUserIds.isEmpty()) {
            return;
        }
        // 逐个 refresh 而不是只 evict：refresh 会顺便把新权限算好写回，
        // 避免这些用户的下一次请求同时触发数据库回源（缓存击穿）
        affectedUserIds.forEach(userId -> permissionResolver.refresh(tenantId, userId));
        log.info("角色权限变更后刷新用户权限缓存 roleId={} 影响用户数={}",
                roleId, affectedUserIds.size());
    }

    private Set<MenuId> toMenuIds(Set<Long> menuIds) {
        if (menuIds == null || menuIds.isEmpty()) {
            return Set.of();
        }
        Set<MenuId> result = new LinkedHashSet<>();
        menuIds.stream().filter(java.util.Objects::nonNull).forEach(id -> result.add(MenuId.of(id)));
        return result;
    }

    private Set<DeptId> toDeptIds(Set<Long> deptIds) {
        if (deptIds == null || deptIds.isEmpty()) {
            return Set.of();
        }
        Set<DeptId> result = new LinkedHashSet<>();
        deptIds.stream().filter(java.util.Objects::nonNull).forEach(id -> result.add(DeptId.of(id)));
        return result;
    }

    /**
     * 解析数据范围。
     *
     * <p>无法识别时<b>收敛到最窄</b>而不是抛异常：
     * 抛异常会让"后台配置里多打了一个字"直接变成一个打不开的页面；
     * 而收敛到 SELF 的后果是"该角色暂时只看得到自己的数据"—— 可发现、可修复，
     * 且不会造成越权。fail-closed。
     */
    private DataScope parseDataScope(String value) {
        if (value == null || value.isBlank()) {
            return DataScope.SELF;
        }
        try {
            return DataScope.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            log.warn("无法识别的数据范围「{}」，已收敛为 SELF", value);
            return DataScope.SELF;
        }
    }
}
