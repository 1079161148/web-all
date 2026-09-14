package com.webadmin.domain.iam.model.tenant;

import com.webadmin.domain.shared.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 租户测试数据构造器。
 *
 * <p>按设计文档 §13.5 要求，测试数据必须用「语义化命名的 Fixture」构造，
 * 而不是在每个测试里堆 10 行 {@code new Tenant(...)}。
 *
 * <p>好处不只是简洁：当聚合的构造参数变化时，只需要改这里一处。
 */
public final class TenantFixture {

    public static final Instant DEFAULT_NOW = Instant.parse("2026-09-14T00:00:00Z");
    public static final Clock FIXED_CLOCK = Clock.fixed(DEFAULT_NOW, ZoneOffset.UTC);

    public static final SubscriptionPlan PRO_PLAN =
            SubscriptionPlan.of("PRO", "专业版", Quota.of(100L, 1024L, 10_000L));

    private TenantFixture() {
    }

    /** 一个刚创建、尚未激活的租户。 */
    public static Tenant aPendingTenant() {
        return Tenant.create(TenantId.of(1001L), TenantCode.of("acme-corp"),
                TenantName.of("Acme 科技"), PRO_PLAN, FIXED_CLOCK);
    }

    /** 一个正常可用的租户。 */
    public static Tenant anActiveTenant() {
        Tenant tenant = aPendingTenant();
        tenant.activate(FIXED_CLOCK);
        return tenant;
    }

    /** 一个已暂停的租户。 */
    public static Tenant aSuspendedTenant() {
        Tenant tenant = anActiveTenant();
        tenant.suspend("欠费", FIXED_CLOCK);
        return tenant;
    }

    /** 一个已关闭（终态）的租户。 */
    public static Tenant aClosedTenant() {
        Tenant tenant = anActiveTenant();
        tenant.close("客户注销", FIXED_CLOCK);
        return tenant;
    }

    /** 一个已过期的租户（状态仍为 ACTIVE，但到期时间已过）。 */
    public static Tenant anExpiredTenant() {
        return Tenant.reconstitute(
                TenantId.of(1001L),
                TenantCode.of("acme-corp"),
                TenantName.of("Acme 科技"),
                TenantStatus.ACTIVE,
                PRO_PLAN,
                PRO_PLAN.initialQuota(),
                ExpireTime.of(DEFAULT_NOW.minusSeconds(86_400)));
    }
}
