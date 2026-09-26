package com.webadmin.interfaces.rest.interceptor;

import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.common.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * 限流拦截器：配合 {@link RateLimit} 注解，Redis 固定窗口计数。
 *
 * <h3>为什么是拦截器而不是 AOP 切面</h3>
 * 限流关注"端点被打了多少次"—— 拦截器天然拿到 handler 方法与请求
 * 上下文（IP、header），且在业务代码之前生效；切面方案还要处理
 * 代理与注解继承细节。
 *
 * <h3>开关（webadmin.security.rate-limit.enabled）</h3>
 * 生产默认开启。集成测试显式关闭（同一身份高频登录会互相触发限流）——
 * 开关放在配置而非删除代码，是为了让限流在生产路径上始终"在场"。
 *
 * <h3>固定窗口的边界突刺</h3>
 * 窗口交界处允许 2x 突刺（前窗末尾 + 后窗开头）。对"防爆破/防刷"足够；
 * 严格平滑换滑窗或令牌桶（Redis+Lua），接口形状不变。
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redis;
    private final boolean enabled;

    public RateLimitInterceptor(StringRedisTemplate redis,
                                @Value("${webadmin.security.rate-limit.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!enabled) {
            return true;
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimit annotation = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (annotation == null) {
            return true;
        }

        String identity = annotation.scope() == RateLimit.Scope.IP
                ? clientIp(request)
                : String.valueOf(request.getAttribute("webadmin.userId") == null
                        ? "ip:" + clientIp(request)
                        : request.getAttribute("webadmin.userId"));
        Long tenant = TenantContext.get().orElse(0L);
        String key = "rl:%s:%s:%s:%s".formatted(
                annotation.scope().name().toLowerCase(),
                tenant == null ? 0 : tenant,
                identity,
                handlerMethod.getMethod().getName());

        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, Duration.ofSeconds(annotation.windowSeconds()));
        }
        if (count != null && count > annotation.limit()) {
            throw new BizException(CommonErrorCode.RATE_LIMITED,
                    "请求过于频繁，请 " + annotation.windowSeconds() + " 秒后再试");
        }
        return true;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
