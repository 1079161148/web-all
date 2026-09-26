package com.webadmin.interfaces.rest.interceptor;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 拦截器注册。
 *
 * 顺序即语义：
 * 1. RateLimitInterceptor 最先执行 —— 超限的请求不该再消耗幂等键的 Redis 写入；
 * 2. IdempotencyInterceptor 只对导入导出工具域生效（写操作域），
 *    且依赖 TenantContextFilter 已建立租户上下文（Filter 先于拦截器）。
 */
@Configuration
@RequiredArgsConstructor
public class ToolsWebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final IdempotencyInterceptor idempotencyInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(idempotencyInterceptor).addPathPatterns("/api/v1/tools/**");
    }
}