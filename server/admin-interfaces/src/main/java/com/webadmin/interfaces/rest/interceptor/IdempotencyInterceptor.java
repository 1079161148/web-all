package com.webadmin.interfaces.rest.interceptor;

import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.common.tenant.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * 幂等拦截器：带 {@code Idempotency-Key} 头的写请求在窗口期内只受理一次。
 *
 * <h3>要防的是什么</h3>
 * "确认导入"按钮被双击 / 网络超时后用户手动重发 —— 两次请求都会到达服务端。
 * 前端禁用按钮挡得住手抖，挡不住重试与网络层重发。
 * 幂等键的语义：<b>同一个业务意图只执行一次</b>。
 *
 * <h3>实现与取舍</h3>
 * Redis {@code SET NX EX}：第一个请求占住键（窗口 15 秒），
 * 窗口内的重放直接以 {@code REPEAT_SUBMIT} 拒绝。
 * <ul>
 *   <li>刻意<b>不缓存首次响应体</b>（完整幂等需要它）：响应序列化、过期策略、
 *       大响应裁剪都会被拖进来。窗口拒绝 + 前端提示已覆盖实际场景；
 *       响应缓存是"完整幂等"的下一步，接口就绪（键里已含租户与用户）</li>
 *   <li>键 = {@code idem:{tenant}:{user}:{key}}：同一键对不同用户互不影响</li>
 *   <li>只拦<b>显式携带键</b>的请求：是否启用幂等由前端按业务语义决定</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final Duration WINDOW = Duration.ofSeconds(15);
    private static final String PREFIX = "idem:";

    private final StringRedisTemplate redis;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            return true;
        }
        Long tenant = TenantContext.get().orElse(0L);
        Object userId = request.getAttribute("webadmin.userId");
        String redisKey = "%s%s:%s:%s".formatted(PREFIX, tenant,
                userId == null ? "anonymous" : userId, key);

        Boolean first = redis.opsForValue().setIfAbsent(redisKey, "1", WINDOW);
        if (first == null || !first) {
            throw new BizException(CommonErrorCode.REPEAT_SUBMIT,
                    "操作正在处理中，请勿重复提交（Idempotency-Key 冲突）");
        }
        return true;
    }
}
