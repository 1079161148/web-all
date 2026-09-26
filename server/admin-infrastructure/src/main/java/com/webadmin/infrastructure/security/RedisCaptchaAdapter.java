package com.webadmin.infrastructure.security;

import com.webadmin.application.iam.port.CaptchaPort;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 验证码的 Redis 实现（存储 + 一次性消费）。
 *
 * <h3>Key 设计</h3>
 * <pre>{@code webadmin:captcha:{uuid}}</pre>
 * 答案直接以小写文本存放（不哈希）。理由：
 * <ul>
 *   <li>TTL 只有几分钟，且<b>校验后立即删除</b> —— 暴露窗口极小</li>
 *   <li>哈希会让"运维排查用户为什么登不上"变成不可能（无法对账），
 *       而它并不是长期的账号凭据（对比：刷新令牌存哈希，因为那是 7~30 天的钥匙）</li>
 * </ul>
 *
 * <h3>消费必须原子</h3>
 * 用 {@code GETDEL}（Spring Data 的 {@code getAndDelete}）一步完成"读取 + 删除"。
 * 若拆成 get + delete，两个并发请求可能<b>同时读到同一个答案</b>并都判定通过 ——
 * 一次性语义会在并发下失效，而爆破脚本恰恰就是并发发请求。
 *
 * <h3>故障语义</h3>
 * 与 {@code RedisRefreshTokenAdapter} 的 fail-closed 取向一致：
 * 存储不可用时签发与校验都返回"失败"（校验返回 false），绝不乐观放行。
 * 代价是 Redis 抖动期间无法登录（而非"登录了但没验证码"）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCaptchaAdapter implements CaptchaPort {

    private static final String PREFIX = "webadmin:captcha:";

    private final StringRedisTemplate redis;
    private final CaptchaImageGenerator generator;

    /**
     * 有效期（默认 180 秒）。
     * 太短：用户还没输完就过期（体验差，反复刷新反而增加服务压力）；
     * 太长：为爆破留出更宽的窗口。3 分钟是常见取值。
     */
    @Value("${webadmin.security.captcha.ttl-seconds:180}")
    private long ttlSeconds;

    @Override
    public Issued issue() {
        String answer = generator.generateText();
        String imageBase64 = generator.renderBase64(answer);
        String captchaId = UUID.randomUUID().toString();
        try {
            redis.opsForValue().set(key(captchaId), answer.toLowerCase(Locale.ROOT),
                    Duration.ofSeconds(ttlSeconds));
        } catch (RuntimeException ex) {
            // 存不进去就绝不能把 id 发给前端：那个验证码永远不会校验通过
            log.error("验证码写入存储失败（期间用户无法登录）", ex);
            throw new IllegalStateException("验证码服务暂不可用，请稍后重试", ex);
        }
        return new Issued(captchaId, imageBase64, ttlSeconds);
    }

    @Override
    public boolean verifyAndConsume(String captchaId, String input) {
        if (captchaId == null || captchaId.isBlank() || input == null || input.isBlank()) {
            return false;
        }
        String stored;
        try {
            stored = redis.opsForValue().getAndDelete(key(captchaId));
        } catch (RuntimeException ex) {
            log.error("验证码校验失败（存储不可用，按拒绝处理）", ex);
            return false;
        }
        if (stored == null || stored.isBlank()) {
            // 不存在 / 已过期 / 已被消费 —— 三者对外一律同样处理（不泄露状态）
            return false;
        }
        return stored.equals(input.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public long expiresInSeconds() {
        return ttlSeconds;
    }

    private static String key(String captchaId) {
        return PREFIX + captchaId;
    }
}
