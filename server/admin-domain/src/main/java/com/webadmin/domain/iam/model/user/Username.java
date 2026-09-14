package com.webadmin.domain.iam.model.user;

import java.util.regex.Pattern;

/**
 * 用户名（值对象）。
 *
 * <p>规则：4~64 位，字母/数字/下划线/连字符/点，必须以字母或数字开头结尾。
 * 刻意**不允许中文与空格** —— 用户名是登录凭据与审计日志的主体标识，
 * 允许中文会带来编码、大小写、显示宽度等一连串问题；
 * 需要中文展示的场景用 {@code nickname}。
 *
 * <p>大小写策略：**统一转小写存储**。理由：MySQL 默认排序规则对
 * VARCHAR 的比较不区分大小写，若应用层不做归一，就会出现
 * "注册了 Admin、登录时输入 admin 也进得去，但按用户名精确查询又查不到"
 * 这种前后矛盾的行为。在入口处归一，比在每一处查询上纠结排序规则可靠得多。
 */
public record Username(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_.-]{2,62}[a-zA-Z0-9]$");

    /** 超管用户名，初始化器与鉴权逻辑依赖此常量，禁止散落硬编码。 */
    public static final String SUPER_ADMIN = "admin";

    public Username {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        String normalized = value.trim().toLowerCase();
        if (!PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "用户名格式不合法：需 4~64 位字母、数字、下划线、连字符或点，且首尾为字母或数字");
        }
        value = normalized;
    }

    public static Username of(String value) {
        return new Username(value);
    }

    /** 从持久化还原（同样走归一化，保证与写入时一致）。 */
    public static Username ofPersisted(String value) {
        return new Username(value);
    }

    public boolean isSuperAdmin() {
        return SUPER_ADMIN.equals(value);
    }
}
