package com.webadmin.infrastructure.security;

import com.webadmin.application.iam.port.AuthStoreUnavailableException;
import com.webadmin.application.iam.port.SessionRegistryPort;
import com.webadmin.application.iam.port.SessionRegistryPort.SessionRecord;
import com.webadmin.application.iam.port.TokenVersionPort;
import com.webadmin.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器：解析令牌 + <b>吊销判定</b> + 确立租户上下文。
 *
 * <h3>吊销判定（本类相对"纯 JWT 校验"新增的职责）</h3>
 * 无状态 JWT 本身无法撤回，这里用两道检查补上（设计见各端口的类注释）：
 * <ol>
 *   <li><b>会话注册表</b>：{@code jti} 对应的会话还在吗？版本号一致吗？
 *       —— 提供<b>会话级</b>吊销（注销设备、定向踢人）与在线列表</li>
 *   <li><b>令牌版本号</b>：令牌里的 {@code ver} 与用户当前版本一致吗？
 *       —— 提供<b>用户级、持久</b>的吊销（改密/停用/强制下线）</li>
 * </ol>
 * 两者都通过才放行；任何一道不满足都按"未认证"处理（由授权链返回 401）。
 *
 * <h3>降级路径：会话注册表不可用 ≠ 会话不存在</h3>
 * Redis 故障时抛 {@link AuthStoreUnavailableException} —— 此时<b>不能</b>把
 * 所有令牌判成已注销（那等于一次 Redis 抖动 = 全站登出），
 * 而是降级为"只校验版本号"：durable 的用户级吊销依然生效，
 * 暂时失去的只是"定向踢某台设备"这种细粒度能力。这是刻意的安全/可用性取舍。
 *
 * <h3>代价声明：鉴权路径不再"零 IO"</h3>
 * 原实现每次请求零外部调用；现在每个已认证请求多<b>两次 Redis 读</b>
 * （会话记录 + 版本号，后者带缓存）。这是吊销能力的固有成本，
 * 无法在"可撤销"与"零查询"之间两全。
 * 缓解手段：活跃时间写入按 60 秒节流、版本号有 Redis 缓存、
 * Redis 故障时降级到数据库 + 进程内缓存。
 *
 * <h3>解析失败时的处理</h3>
 * <b>不抛异常、不写 401，直接放行</b>。理由：过滤器无法区分"这个接口需要认证"
 * 与"这个接口是公开的"。由后续的授权链决定：需要认证的接口因无 Authentication
 * 而被 EntryPoint 拒绝（401），公开接口（如登录）则正常放行。
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_PREFIX = "ROLE_";

    /** 活跃时间的写入节流窗口（与 RedisSessionRegistryAdapter 内部的节流双保险）。 */
    private static final Duration ACTIVITY_WINDOW = Duration.ofSeconds(60);

    private final JwtTokenProvider tokenProvider;
    private final SessionRegistryPort sessionRegistry;
    private final TokenVersionPort tokenVersionPort;

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

            // ---- 吊销判定所需的三个声明 ----
            String sessionId = jwt.getId();
            long tokenVersion = toLong(jwt.getClaim("ver"));
            long userId = toLong(jwt.getClaim("userId"));
            long tenantId = toLong(jwt.getClaim("tenantId"));

            if (sessionId == null || sessionId.isBlank()
                    || userId <= 0 || tenantId <= 0 || tokenVersion < 0) {
                // 缺 jti / ver 的令牌无法参与吊销体系（旧版本签发、或手工构造）。
                // 一律拒绝（fail-closed）：部署本版本后，存量旧令牌需要重新登录一次，
                // 这是"让吊销成为可能"的一次性代价
                log.debug("令牌缺少会话/版本声明，按未认证处理 userId={}", userId);
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            if (!isRevocationPassed(tenantId, userId, sessionId, tokenVersion)) {
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

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
            TenantContext.set(tenantId);
        } catch (JwtException | IllegalArgumentException ex) {
            // 令牌无效：清空上下文后放行，由授权链决定是否拒绝。
            // 必须清空 —— 否则可能残留上一个请求在同线程上的认证信息（线程复用）。
            SecurityContextHolder.clearContext();
            log.debug("令牌解析失败，按未认证处理：{}", ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 吊销判定：会话注册表优先，存储故障时降级为版本号校验。
     *
     * @return true 表示令牌未被吊销，可以继续构建认证信息
     */
    private boolean isRevocationPassed(long tenantId, long userId,
                                       String sessionId, long tokenVersion) {
        try {
            Optional<SessionRecord> session = sessionRegistry.find(tenantId, userId, sessionId);
            if (session.isEmpty()) {
                // 不在注册表 = 已注销 / 被踢 / 已过期（"不在即已吊销"，见端口注释）
                log.debug("会话不存在，令牌已吊销 userId={} sessionId={}", userId, sessionId);
                return false;
            }
            if (session.get().tokenVersion() != tokenVersion) {
                // 会话注册之后发生过用户级吊销（改密/停用/强退）→ 连会话一起作废
                log.debug("令牌版本与会话记录不一致，已吊销 userId={} sessionId={}", userId, sessionId);
                return false;
            }
            // ⚠️ 还必须与"用户当前版本"比对 —— 只有会话记录与令牌互相一致是不够的：
            // 两者可能同时是旧的（典型：租户级强制下线批量提升了全部用户的版本号，
            // 会话记录与令牌都没变 → 互相一致 → 若不查当前版本，踢人形同虚设。
            // 这一条实测漏过：停用租户后在线令牌依然可用）。
            // 版本号有 Redis 缓存，热路径代价是一次额外 GET。
            long currentVersion = tokenVersionPort.current(tenantId, userId);
            if (currentVersion < 0 || currentVersion != tokenVersion) {
                // -1 表示用户已不存在（已删除）→ 一律拒绝
                log.debug("令牌版本落后于当前版本，已吊销 userId={} sessionId={}", userId, sessionId);
                return false;
            }
            touchIfNeeded(session.get());
            return true;
        } catch (AuthStoreUnavailableException ex) {
            // 降级：注册表不可用 → 只校验版本号。
            // 用户级（durable）吊销仍然生效；会话级吊销在此期间不可判定 —— 刻意的取舍
            long current = tokenVersionPort.current(tenantId, userId);
            boolean allowed = current >= 0 && current == tokenVersion;
            log.warn("会话注册表不可用，降级为仅校验令牌版本号 userId={} 放行={}", userId, allowed, ex);
            return allowed;
        }
    }

    /** 活跃时间超出窗口才写一次（见 {@link #ACTIVITY_WINDOW} 与适配器内的节流）。 */
    private void touchIfNeeded(SessionRecord session) {
        if (session.lastActive() == null
                || Duration.between(session.lastActive(), Instant.now()).compareTo(ACTIVITY_WINDOW) >= 0) {
            sessionRegistry.touch(session.tenantId(), session.userId(), session.sessionId());
        }
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
