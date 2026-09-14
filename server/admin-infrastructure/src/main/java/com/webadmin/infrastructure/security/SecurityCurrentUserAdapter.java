package com.webadmin.infrastructure.security;

import com.webadmin.application.security.CurrentUser;
import com.webadmin.application.security.CurrentUserPort;
import com.webadmin.common.tenant.TenantContext;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * 从 Spring Security 上下文中读取当前用户。
 *
 * <h3>为什么 tenantId 优先取 JWT 声明而不是 TenantContext</h3>
 * 两者在正常路径下应当一致（{@code JwtAuthenticationFilter} 已把声明写入 TenantContext）。
 * 但存在不一致的可能：本地联调时可能只带 {@code X-Tenant-Id} 头、不带令牌。
 * 此时以 <b>JWT 声明为准</b> —— 它是经过签名的、不可伪造的；
 * 请求头是客户端可任意构造的。
 *
 * <p><b>安全的默认值必须来自可信来源。</b>哪怕只是"读哪个字段"这种小选择，
 * 一旦搞反，就等于把租户隔离的根基交给了客户端。
 */
@Component
public class SecurityCurrentUserAdapter implements CurrentUserPort {

    @Override
    public Optional<CurrentUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            return fromJwt(jwtAuth.getToken());
        }
        return Optional.empty();
    }

    private Optional<CurrentUser> fromJwt(Jwt jwt) {
        Object userIdClaim = jwt.getClaim("userId");
        Object tenantIdClaim = jwt.getClaim("tenantId");
        if (userIdClaim == null || tenantIdClaim == null) {
            // 令牌里没有身份声明 → 视为无效，绝不猜测或回退到请求头。
            // 这类令牌通常来自手工构造或旧版本签发，必须拒绝而不是"尽力而为"。
            return Optional.empty();
        }
        long userId = toLong(userIdClaim);
        long tenantId = toLong(tenantIdClaim);
        if (userId <= 0) {
            return Optional.empty();
        }
        String username = jwt.getClaimAsString("username");
        if (username == null) {
            username = jwt.getSubject();
        }
        // 兜底：正常流程下 JwtAuthenticationFilter 已写入 TenantContext；
        // 但在仅带令牌、过滤器顺序异常等情况下，这里补一次设置，保证后续拦截器拿到正确租户。
        if (TenantContext.get().isEmpty()) {
            TenantContext.set(tenantId);
        }
        return Optional.of(new CurrentUser(userId, tenantId, username));
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }
}
