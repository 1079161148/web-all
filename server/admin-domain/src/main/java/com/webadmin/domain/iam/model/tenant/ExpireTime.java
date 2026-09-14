package com.webadmin.domain.iam.model.tenant;

import com.webadmin.domain.iam.exception.TenantExpireTimeInvalidException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * 租户到期时间（值对象）。
 *
 * <p>不变量：不得为空。时间统一以 UTC 存储（设计文档 §9.2）。
 *
 * <p>提供「月」粒度续期能力：SaaS 套餐按自然月计费，用 {@link Period} 而非
 * {@link Duration} 表达，避免 30 天 ≠ 1 个月带来的账期偏差。
 */
public record ExpireTime(Instant value) {

    public ExpireTime {
        if (value == null) {
            throw new TenantExpireTimeInvalidException("到期时间不能为空");
        }
    }

    public static ExpireTime of(Instant value) {
        return new ExpireTime(value);
    }

    public static ExpireTime now(Clock clock) {
        return new ExpireTime(clock.instant());
    }

    /**
     * 按自然月/年续期。
     *
     * <p>用 UTC 的 {@link ZonedDateTime} 做日历运算，支持 {@code Period.ofMonths(1)} 这类
     * 会因月末天数不同而变化的语义（1/31 加 1 个月 → 2/28 或 2/29）。
     */
    public ExpireTime plus(Period period) {
        if (period == null || period.isNegative() || period.isZero()) {
            throw new TenantExpireTimeInvalidException("续期周期必须为正数");
        }
        return new ExpireTime(value.atZone(ZoneOffset.UTC).plus(period).toInstant());
    }

    public ExpireTime plusMonths(int months) {
        return plus(Period.ofMonths(months));
    }

    public ExpireTime plusDays(long days) {
        if (days <= 0) {
            throw new TenantExpireTimeInvalidException("续期天数必须为正数");
        }
        return new ExpireTime(value.plus(Duration.ofDays(days)));
    }

    /** 是否晚于指定时刻（即尚未到期）。 */
    public boolean isAfter(Instant instant) {
        return value.isAfter(instant);
    }

    public boolean isExpired(Instant now) {
        return !value.isAfter(now);
    }

    /** 剩余天数，已过期返回 0。 */
    public long remainingDays(Instant now) {
        if (isExpired(now)) {
            return 0L;
        }
        return Duration.between(now, value).toDays();
    }
}
