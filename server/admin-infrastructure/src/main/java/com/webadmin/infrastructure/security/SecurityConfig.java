package com.webadmin.infrastructure.security;

import com.webadmin.common.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security 配置。
 *
 * <h3>{@code @EnableMethodSecurity} 是权限闭环的开关</h3>
 * 没有它，Controller 上写的 {@code @PreAuthorize} <b>会被静默忽略</b> ——
 * 注解在、代码看起来有鉴权、实际毫无作用。这是最危险的一类错误：
 * 它不会报错，只会让系统"看起来有权限控制"。
 * 因此 {@code /quality:check-arch} 把"是否存在 @EnableMethodSecurity"
 * 列为必查项，并有集成测试断言"无权限调用确实返回 403"。
 *
 * <h3>为什么用无状态会话</h3>
 * 令牌自带身份，服务端不存会话 → 水平扩容无需会话粘滞。
 * 代价是"踢人下线"依赖 Redis 令牌黑名单或权限缓存失效，
 * 这部分由 {@code PermissionResolver} 的精确失效能力承担。
 *
 * <h3>公开端点清单为什么是显式的</h3>
 * 用 {@code permitAll()} 明确列出而不是靠路径通配，是为了让"哪些接口不需要认证"
 * 成为一处可审计的清单。任何新增的公开接口都必须在这里出现，
 * 代码评审时一眼可见 —— 而不是藏在某个 {@code /**} 通配里。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /** 无需认证的端点。新增时必须在此显式声明，禁止用 /** 通配掩盖。 */
    private static final List<String> PUBLIC_ENDPOINTS = List.of(
            // 认证入口
            "/api/v1/auth/login",
            "/api/v1/auth/captcha",
            "/api/v1/auth/refresh",
            // OpenAPI 契约与文档页（生产应通过反向代理屏蔽，见设计文档 §14.2）
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            // 健康检查与指标（供 K8s 探针与 Prometheus 抓取）
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus"
    );

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtTokenProvider tokenProvider)
            throws Exception {
        http
                // 前后端分离 + 无状态令牌，不使用 Cookie 会话，故无需 CSRF 保护。
                // 将来若引入 BFF（Cookie 承载令牌），必须同步开启 CSRF —— 届时这条注释要一起改。
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // 预检请求必须放行，否则浏览器端的跨域调用会在 OPTIONS 阶段就失败
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    auth.requestMatchers(PUBLIC_ENDPOINTS.toArray(String[]::new)).permitAll();
                    // 其余一律要求认证：这是 fail-closed 的默认值。
                    // 新增业务接口忘了加权限注解时，至少还有"必须登录"这一层兜底。
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        CommonErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((request, response, accessDeniedException) ->
                                writeError(response, HttpServletResponse.SC_FORBIDDEN,
                                        CommonErrorCode.ACCESS_DENIED)))
                .addFilterBefore(new JwtAuthenticationFilter(tokenProvider),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * CORS 配置。
     *
     * <p>开发期允许 localhost 各端口；生产环境应通过环境变量注入白名单，
     * <b>绝不能留 {@code *}</b> —— 允许任意来源 + 允许携带凭证等于把接口开放给所有网站。
     * 这里 {@code allowCredentials(false)} 也是刻意的：改用 Cookie 方案时
     * 必须同时收紧来源白名单，那是一个需要显式决策的变更。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("Content-Disposition"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /**
     * 把安全异常写成项目统一的 {@code R} 结构。
     *
     * <p>为什么不让 Spring Security 返回默认的 HTML/空响应：
     * 前端的请求封装只认 {@code R{code,msg,data}}，统一格式才能让
     * "令牌过期 → 弹出重新登录"这类逻辑只写一处。
     *
     * <h3>为什么手拼 JSON 而不注入 ObjectMapper</h3>
     * Spring Boot 4 已把默认 JSON 库从 Jackson 2 换成 Jackson 3
     * （包名 {@code com.fasterxml.jackson} → {@code tools.jackson}）。
     * 若在这里注入 ObjectMapper，就把一个<b>安全基础设施</b>与
     * 一个<b>可能再次升级的序列化库版本</b>绑在了一起 ——
     * 而这处序列化只用到一个 4 字段的固定结构，手拼的成本远低于耦合成本。
     *
     * <p>{@code msg} 来自枚举常量（受控文本），这里仍做一次引号转义，
     * 以防将来有人在错误码里写上带引号的文案导致响应体破损。
     */
    private void writeError(HttpServletResponse response, int httpStatus,
                            CommonErrorCode errorCode) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String msg = errorCode.message().replace("\\", "\\\\").replace("\"", "\\\"");
        String body = "{\"code\":" + errorCode.code()
                + ",\"msg\":\"" + msg
                + "\",\"data\":null,\"success\":false}";
        response.getWriter().write(body);
    }
}
