package com.webadmin.infrastructure.security;

import com.webadmin.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器。
 *
 * <h3>它做两件事，第二件容易被忽略</h3>
 * <ol>
 *   <li>解析 {@code Authorization: Bearer xxx}，校验通过则写入 SecurityContext</li>
 *   <li><b>把令牌里的 {@code tenantId} 写入 {@link TenantContext}</b></li>
 * </ol>
 * 第二件极其关键：多租户隔离依赖 {@code TenantContext}，而请求头是可伪造的。
 * 由签名过的令牌来确立租户，才谈得上"隔离"。这也是为什么本过滤器在
 * {@code TenantContextFilter} 之后运行 —— 让令牌覆盖掉请求头里的值。
 *
 * <h3>解析失败时的处理</h3>
 * <b>不抛异常、不写 401，直接放行</b>。理由：过滤器无法区分"这个接口需要认证"
 * 与"这个接口是公开的"（前者如 /api/v1/users，后者如 /api/v1/auth/login）。
 * 由后续的授权链决定：需要认证的接口因无 Authentication 而被 EntryPoint 拒绝（401），
 * 公开接口则正常放行。
 *
 * <p>若在这里就返回 401，会导致"带着过期令牌访问登录接口"这种边界场景直接失败 ——
 * 而用户的意图明明是重新登录。
 *
 * <h3>不查库</h3>
 * 令牌校验是纯密码学操作；用户名/租户/角色都来自令牌声明。
 * 这样鉴权路径完全无 IO，是虚拟线程模型下最理想的形态。
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    private final JwtTokenProvider tokenProvider;

    @Override
    @SuppressWarnings("unchecked")
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Jwt jwt = tokenProvider.decode(token);

            Object roleClaim = jwt.getClaim("roles");
            List<GrantedAuthority> authorities = new ArrayList<>();
            if (roleClaim instanceof Collection<?> roles) {
                for (Object role : roles) {
                    if (role != null) {
                        // 角色加上 ROLE_ 前缀，使 hasRole('SUPER_ADMIN') 可命中；
                        // 权限码不在令牌里，因此这里不注册权限类 authority ——
                        // 细粒度鉴权统一走 @PreAuthorize("@ps.hasPermission(...)")。
                        authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + role));
                    }
                }
            }

            JwtAuthenticationToken authentication =
                    new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // ★ 用可信来源（签名令牌）确立租户上下文，覆盖可能被伪造的请求头
            Object tenantClaim = jwt.getClaim("tenantId");
            if (tenantClaim != null) {
                TenantContext.set(Long.parseLong(String.valueOf(tenantClaim)));
            }
        } catch (JwtException | IllegalArgumentException ex) {
            // 令牌无效：清空上下文后放行，由授权链决定是否拒绝。
            // 必须清空 —— 否则可能残留上一个请求在同线程上的认证信息（线程复用）。
            SecurityContextHolder.clearContext();
            log.debug("令牌解析失败，按未认证处理：{}", ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }
}
