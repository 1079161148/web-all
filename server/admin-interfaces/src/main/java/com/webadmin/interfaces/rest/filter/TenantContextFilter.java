package com.webadmin.interfaces.rest.filter;

import com.webadmin.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 租户上下文装配过滤器。
 *
 * <h3>⚠️ 这是 P0 骨架的临时实现</h3>
 * 当前从请求头 {@code X-Tenant-Id} 读取租户 ID。P1 接入 Authorization Server 后，
 * 应改为<b>从已验证的 JWT 声明中读取</b>：
 *
 * <pre>{@code
 * // P1 目标实现（伪代码）
 * Long tenantId = jwtAuthentication.getToken().getClaimAsLong("tenantId");
 * }</pre>
 *
 * <p><b>为什么不现在就这么做</b>：从请求头读取是<b>不安全的</b>（客户端可伪造任意租户），
 * 仅用于骨架阶段让接口可被调用与测试。这正是它在代码里被明确标记为临时实现的原因 ——
 * <b>绝不能带着这个实现上线</b>。
 *
 * <h3>职责（顺序很关键，不要随意调整 @Order）</h3>
 * <ul>
 *   <li><b>必须排在 Spring Security 过滤器链之前</b>（Security 默认优先级约 -100，
 *       这里取 {@code HIGHEST_PRECEDENCE + 10}）。原因有二：
 *       ① 它是整个请求最外层的"清理者"，<b>只有最外层才能保证 finally 一定执行</b> ——
 *          若排在 Security 之后，一旦鉴权失败提前返回，Security 内部
 *          （{@code JwtAuthenticationFilter}）写入的租户上下文就没人清理，
 *          这个线程被复用时会带着别人的租户身份 —— 正是跨租户泄露的成因；
 *       ② 它先按请求头写入，随后 Security 内的 JWT 过滤器用<b>签名令牌</b>覆盖，
 *          天然形成「令牌优先于请求头」的优先级</li>
 *   <li>请求结束时清理上下文（在 finally 中）</li>
 * </ul>
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Component
public class TenantContextFilter extends OncePerRequestFilter {

    /**
     * 租户标识请求头（<b>仅开发期兜底</b>）。
     *
     * <p>约定：{@code 0} 表示平台级请求（超管操作平台数据、租户列表等），
     * 与 {@code TenantContext.PLATFORM_TENANT_ID} 保持一致。
     */
    private static final String TENANT_HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            resolveTenantId(request).ifPresent(TenantContext::set);
            filterChain.doFilter(request, response);
        } finally {
            // 必须放在 finally：即使业务抛异常也要清理，否则污染下一个复用该线程的请求
            TenantContext.clear();
        }
    }

    private Optional<Long> resolveTenantId(HttpServletRequest request) {
        String header = request.getHeader(TENANT_HEADER);
        if (header == null || header.isBlank()) {
            // 未携带租户头的请求（登录、健康检查、OpenAPI 文档等）不设置上下文，
            // 若后续访问了需要租户隔离的表，TenantLineHandler 会抛异常快速失败，
            // 而不是静默返回错误租户的数据
            return Optional.empty();
        }
        try {
            long tenantId = Long.parseLong(header.trim());
            if (tenantId < 0) {
                return Optional.empty();
            }
            return Optional.of(tenantId);
        } catch (NumberFormatException ex) {
            logger.warn("非法的 " + TENANT_HEADER + " 请求头: " + header);
            return Optional.empty();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 运维与文档端点无需租户上下文
        return uri.startsWith("/actuator")
                || uri.startsWith("/v3/api-docs")
                || uri.startsWith("/swagger-ui");
    }
}
