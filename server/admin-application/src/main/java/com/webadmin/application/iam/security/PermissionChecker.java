package com.webadmin.application.iam.security;

import com.webadmin.application.security.CurrentUser;
import com.webadmin.application.security.CurrentUserPort;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 权限断言入口，供 {@code @PreAuthorize} 的 SpEL 调用。
 *
 * <h3>用法</h3>
 * <pre>{@code
 * @PreAuthorize("@ps.hasPermission('iam:user:create')")
 * @PostMapping("/users")
 * public R<Long> create(...) { ... }
 * }</pre>
 * Bean 名固定为 {@code ps}（permission service），这是全项目统一约定 ——
 * 短名字在注解里出现频率极高，写得越短越不容易手误。
 *
 * <h3>为什么权限码用字符串而不是枚举</h3>
 * 枚举常量在注解里必须写成常量表达式（{@code @PreAuthorize} 里做不到，
 * 那是 SpEL 而非编译期常量），且新增权限点就要改枚举 + 重新编译 + 部署。
 * 权限码本质是<b>配置数据</b>（存在 {@code iam_menu.perms} 里、可在后台维护），
 * 因此用字符串是符合其数据本质的选择。
 *
 * <p>代价是拼写错误不会被编译器发现。补偿手段有两个：
 * <ul>
 *   <li>种子数据与前端菜单权限码同源（都来自 {@code iam_menu}），不存在两处手写</li>
 *   <li>{@code /quality:check-arch} 扫描"Controller 用了 {@code @PreAuthorize} 但
 *       权限码在 {@code iam_menu} 中不存在"的情况</li>
 * </ul>
 *
 * <h3>未登录时的行为</h3>
 * 返回 {@code false} 而非抛异常。理由：{@code @PreAuthorize} 返回 false 时
 * Spring Security 会抛 {@code AccessDeniedException}，由统一的异常处理器
 * 翻译成 403 —— 让框架负责"拒绝"的表达，我们只负责"判断"。
 */
@Component("ps")
@RequiredArgsConstructor
public class PermissionChecker {

    private final CurrentUserPort currentUserPort;
    private final PermissionResolver permissionResolver;

    /** 是否拥有指定权限码。 */
    public boolean hasPermission(String permissionCode) {
        Optional<CurrentUser> current = currentUserPort.currentUser();
        if (current.isEmpty()) {
            return false;
        }
        CurrentUser user = current.get();
        return permissionResolver.has(user.tenantId(), user.userId(), permissionCode);
    }

    /** 是否拥有其中任意一个权限码。 */
    public boolean hasAnyPermission(String... permissionCodes) {
        Optional<CurrentUser> current = currentUserPort.currentUser();
        if (current.isEmpty()) {
            return false;
        }
        CurrentUser user = current.get();
        return permissionResolver.hasAny(user.tenantId(), user.userId(), permissionCodes);
    }

    /** 是否拥有全部指定权限码。 */
    public boolean hasAllPermissions(String... permissionCodes) {
        Optional<CurrentUser> current = currentUserPort.currentUser();
        if (current.isEmpty()) {
            return false;
        }
        for (String code : permissionCodes) {
            if (!hasPermission(code)) {
                return false;
            }
        }
        return true;
    }

    /** 是否拥有指定角色。 */
    public boolean hasRole(String roleKey) {
        Optional<CurrentUser> current = currentUserPort.currentUser();
        if (current.isEmpty()) {
            return false;
        }
        CurrentUser user = current.get();
        return permissionResolver.hasRole(user.tenantId(), user.userId(), roleKey);
    }

    /** 是否超级管理员（用于"仅超管可见"这类平台级操作）。 */
    public boolean isSuperAdmin() {
        Optional<CurrentUser> current = currentUserPort.currentUser();
        if (current.isEmpty()) {
            return false;
        }
        CurrentUser user = current.get();
        return permissionResolver.hasRole(
                user.tenantId(), user.userId(),
                com.webadmin.domain.iam.model.role.RoleKey.SUPER_ADMIN);
    }
}
