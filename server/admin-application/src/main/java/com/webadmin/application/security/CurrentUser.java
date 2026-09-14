package com.webadmin.application.security;

/**
 * 当前登录用户的最小信息（应用层自己的类型，不泄露框架类型）。
 *
 * <h3>为什么只放这三个字段</h3>
 * 刻意<b>不</b>在这里放权限集合与角色列表：
 * <ul>
 *   <li>它们体积可能很大（几百个权限码），而 CurrentUser 会被频繁传递、放进日志、
 *       参与序列化 —— 让一个"身份标识"携带几百个字符串是明显的浪费</li>
 *   <li>权限需要按需解析并缓存，解析本身要访问仓库/Redis，
 *       不适合在每次"取当前用户"时就同步发生</li>
 * </ul>
 * 需要权限时调 {@code PermissionResolver}，它有缓存。
 *
 * <h3>为什么不直接用 Spring Security 的 Authentication</h3>
 * 那样会让 application 层依赖 {@code spring-security-core}。
 * 一旦应用层能直接读 {@code SecurityContextHolder}，就会出现
 * "在领域服务里偷偷读安全上下文"这种不可测试、不可复用的代码。
 * 用一个应用层自己的 record + 端口，把这份能力限制在明确的边界内。
 */
public record CurrentUser(long userId, long tenantId, String username) {

    /** 平台级（未绑定租户）标识。 */
    public boolean isPlatformLevel() {
        return tenantId == 0L;
    }
}
