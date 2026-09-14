package com.webadmin.application.security;

import java.util.Optional;

/**
 * 当前登录用户提供者（端口）。
 *
 * <p>实现位于 infrastructure（从 Spring Security 的 SecurityContext 中读取）。
 * 应用层只依赖这个接口，因此：
 * <ul>
 *   <li>单元测试里可以直接注入 {@code () -> Optional.of(new CurrentUser(1L, 1L, "admin"))}，
 *       不需要启动 Spring Security 上下文</li>
 *   <li>将来若改为从 gRPC context / 消息头读取身份（服务间调用），
 *       只需换实现，应用层零改动</li>
 * </ul>
 */
@FunctionalInterface
public interface CurrentUserPort {

    /** 当前用户；未登录（如定时任务、匿名接口）时返回空。 */
    Optional<CurrentUser> currentUser();

    /**
     * 取当前用户，缺失即抛异常。
     *
     * <p>用于"没有登录用户就是程序错误"的场景（如写接口）。
     * 与 {@code TenantContext.require()} 同一思路：<b>宁可快速失败，
     * 也不要返回一个伪造的默认身份去执行写操作</b>。
     */
    default CurrentUser requireCurrentUser() {
        return currentUser().orElseThrow(
                () -> new IllegalStateException("当前请求没有登录用户上下文"));
    }
}
