package com.webadmin.domain.iam.model.role;

import java.util.regex.Pattern;

/**
 * 角色标识（值对象）。
 *
 * <p>统一<b>大写 + 下划线</b>存储。与 {@code Username} 的小写归一化同理：
 * 角色标识会出现在 {@code @PreAuthorize("hasRole('SUPER_ADMIN')")}` 这类表达式里，
 * 大小写不一致会导致"配置看起来对、鉴权就是不通过"这种极难排查的问题。
 * 在唯一入口处归一，比在每个使用点上小心谨慎可靠。
 *
 * <p>前缀 {@code ROLE_} 由 Spring Security 约定（{@code hasRole} 会自动加），
 * 因此<b>业务侧不应带前缀</b> —— 否则会变成 {@code ROLE_ROLE_ADMIN}。
 * 这个约束在这里显式拒绝，让错误在创建时就暴露。
 */
public record RoleKey(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,62}[A-Z0-9]$");
    private static final String SPRING_ROLE_PREFIX = "ROLE_";

    /** 超管角色标识。 */
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    public RoleKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("角色标识不能为空");
        }
        String normalized = value.trim().toUpperCase();
        if (normalized.startsWith(SPRING_ROLE_PREFIX)) {
            throw new IllegalArgumentException(
                    "角色标识不要带 " + SPRING_ROLE_PREFIX + " 前缀：Spring Security 的 hasRole() 会自动添加，"
                            + "带上会变成 ROLE_ROLE_XXX");
        }
        if (!PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "角色标识格式不合法：需 3~64 位大写字母、数字或下划线，以字母开头");
        }
        value = normalized;
    }

    public static RoleKey of(String value) {
        return new RoleKey(value);
    }

    public static RoleKey ofPersisted(String value) {
        return new RoleKey(value);
    }

    public boolean isSuperAdmin() {
        return SUPER_ADMIN.equals(value);
    }

    /** 转换为 Spring Security 的角色名（带前缀）。 */
    public String toSpringRole() {
        return SPRING_ROLE_PREFIX + value;
    }
}
