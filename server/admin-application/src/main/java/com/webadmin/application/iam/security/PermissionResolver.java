package com.webadmin.application.iam.security;

import com.webadmin.application.iam.port.PermissionCachePort;
import com.webadmin.application.iam.port.PermissionCachePort.CachedPermissions;
import com.webadmin.domain.iam.model.menu.Menu;
import com.webadmin.domain.iam.model.role.DataScope;
import com.webadmin.domain.iam.model.role.Role;
import com.webadmin.domain.iam.repository.MenuRepository;
import com.webadmin.domain.iam.repository.RoleRepository;
import com.webadmin.domain.iam.repository.UserRepository;
import com.webadmin.domain.shared.MenuId;
import com.webadmin.domain.shared.UserId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 权限解析服务：把「用户 → 角色 → 菜单.perms」算成一组权限码，并做缓存。
 *
 * <h3>判定链</h3>
 * <pre>
 *   用户 --(iam_user_role)--> 角色 --(iam_role_menu)--> 菜单.perms
 *                                                    → 权限码集合
 * </pre>
 *
 * <h3>为什么"超管"是一个独立分支，而不是"拥有全部权限码"</h3>
 * 如果超管靠"权限集合包含所有 code"来判定，那么<b>每次新增一个权限点，
 * 超管就少了这一项</b> —— 表现为"刚上线的功能，超管点不了"，
 * 而每次都得记得同步更新超管的 role_menu。这是典型的脆弱设计。
 * 因此这里用角色标识 {@code SUPER_ADMIN} 直接短路。
 *
 * <h3>缓存写回时机</h3>
 * 只在<b>解析成功后</b>写缓存。若把空集合也写进去，
 * 一次瞬时故障（比如角色查询超时）就会把"该用户没权限"这个错误结论缓存住，
 * 造成持续数分钟的权限异常。宁可下次再查一遍。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionResolver {

    private final RoleRepository roleRepository;
    private final MenuRepository menuRepository;
    private final UserRepository userRepository;
    private final PermissionCachePort permissionCache;

    /**
     * 解析某用户的权限（优先读缓存）。
     *
     * <p>先查缓存、未命中再回源，回源成功后写回。这是标准的 Cache-Aside。
     */
    public CachedPermissions resolve(long tenantId, long userId) {
        var cached = permissionCache.get(tenantId, userId);
        if (cached.isPresent()) {
            return cached.get();
        }
        CachedPermissions computed = computeAndCache(tenantId, userId);
        return computed;
    }

    /**
     * 强制回源并刷新缓存（权限变更后调用）。
     *
     * <p>与 {@link #resolve} 的区别是<b>一定会重新查库</b>，
     * 避免了"刚改完权限，读到的还是旧缓存"这种让人怀疑人生的现象。
     */
    public CachedPermissions refresh(long tenantId, long userId) {
        permissionCache.evict(tenantId, userId);
        return computeAndCache(tenantId, userId);
    }

    /** 权限是否包含指定权限码。 */
    public boolean has(long tenantId, long userId, String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            // 空权限码视为"不校验"是不可接受的默认放开，因此直接拒绝并告警。
            // 这类问题通常来自注解写错（如 hasPermission("")），必须有明确反馈。
            log.warn("权限校验收到空权限码，已按拒绝处理 tenantId={} userId={}", tenantId, userId);
            return false;
        }
        return resolve(tenantId, userId).has(permissionCode);
    }

    /** 是否拥有其中任意一个权限码。 */
    public boolean hasAny(long tenantId, long userId, String... permissionCodes) {
        if (permissionCodes == null || permissionCodes.length == 0) {
            return false;
        }
        CachedPermissions permissions = resolve(tenantId, userId);
        for (String code : permissionCodes) {
            if (permissions.has(code)) {
                return true;
            }
        }
        return false;
    }

    /** 是否拥有指定角色标识。 */
    public boolean hasRole(long tenantId, long userId, String roleKey) {
        if (roleKey == null || roleKey.isBlank()) {
            return false;
        }
        CachedPermissions permissions = resolve(tenantId, userId);
        if (permissions.superAdmin()) {
            return true;
        }
        return permissions.roleKeys().contains(roleKey.toUpperCase());
    }

    private CachedPermissions computeAndCache(long tenantId, long userId) {
        List<Role> roles = roleRepository.findByUserId(userId);

        Set<String> roleKeys = new LinkedHashSet<>();
        Set<MenuId> menuIds = new LinkedHashSet<>();
        Set<DataScope> scopes = new LinkedHashSet<>();
        Set<Long> customDeptIds = new LinkedHashSet<>();
        boolean superAdmin = false;

        for (Role role : roles) {
            if (!role.isUsable()) {
                // 停用的角色不参与鉴权 —— 这是"停用角色立即生效"的实现点。
                // 如果这里不过滤，就必须等缓存过期才生效，运维会以为停用没起作用。
                continue;
            }
            roleKeys.add(role.roleKey().value());

            // 数据范围在多角色下的合并规则：取**最宽**的那个。
            // 这与"权限码取并集"一致 —— 用户拥有多个角色时，能力应当是累加而非收窄。
            scopes.add(role.dataScope());
            if (role.dataScope() == DataScope.CUSTOM) {
                // 只收集 CUSTOM 角色的自定义部门；其他范围的部门对结果没有影响
                role.deptIds().forEach(deptId -> customDeptIds.add(deptId.value()));
            }

            if (role.isSuperAdmin()) {
                superAdmin = true;
                // 超管不再继续收集菜单：下面的解析会被跳过，
                // 避免在拥有数千菜单的租户里做一次无谓的大 IN 查询
                break;
            }
            menuIds.addAll(role.menuIds());
        }

        Set<String> permissionCodes = superAdmin
                ? Set.of()
                : resolvePermsFromMenus(menuIds);
        Set<String> finalRoleKeys = superAdmin
                ? Set.of(com.webadmin.domain.iam.model.role.RoleKey.SUPER_ADMIN)
                : roleKeys;

        // 超管的数据范围视作 ALL，使其不会被 @DataScope 拦成"只看自己"
        // （@DataScope.ignoreSuperAdmin 默认也是 true，两处一致）
        DataScope widest = superAdmin ? DataScope.ALL : DataScope.widestOf(scopes);

        Long deptId = userRepository.findDeptId(UserId.of(userId)).orElse(null);

        CachedPermissions computed = CachedPermissions.of(
                permissionCodes, finalRoleKeys, superAdmin, widest, deptId, customDeptIds);
        permissionCache.put(tenantId, userId, computed);
        log.debug("权限解析完成 tenantId={} userId={} 角色数={} 权限码数={} 超管={} 数据范围={} 部门={} 自定义部门数={}",
                tenantId, userId, finalRoleKeys.size(), permissionCodes.size(), superAdmin,
                widest, deptId, customDeptIds.size());
        return computed;
    }

    private Set<String> resolvePermsFromMenus(Set<MenuId> menuIds) {
        if (menuIds.isEmpty()) {
            return Set.of();
        }
        List<Menu> menus = menuRepository.findAllByIds(menuIds);
        Set<String> codes = new LinkedHashSet<>();
        for (Menu menu : menus) {
            if (menu.isGrantablePermission()) {
                codes.add(menu.perms());
            }
        }
        return codes;
    }
}
