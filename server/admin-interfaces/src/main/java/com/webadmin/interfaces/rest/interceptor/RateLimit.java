package com.webadmin.interfaces.rest.interceptor;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流（固定窗口计数，Redis INCR + EXPIRE）。
 *
 * <p>标注在 Controller 方法上；键 = {@code rl:{scope}:{tenant}:{identity}}。
 * identity 优先取登录用户 id（需要认证），未认证接口（如登录）回退到
 * 客户端 IP —— 防爆破与防刷是它最常见的两个用途。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 窗口期内允许的最大请求数。 */
    int limit();

    /** 窗口大小（秒）。 */
    int windowSeconds() default 60;

    /**
     * 限流维度：{@code USER}（按登录用户，未认证回退 IP）或 {@code IP}。
     */
    Scope scope() default Scope.USER;

    enum Scope { USER, IP }
}
