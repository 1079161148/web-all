package com.webadmin.domain.iam.model.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.webadmin.domain.iam.event.TenantEvent;
import com.webadmin.domain.iam.exception.IllegalTenantStateException;
import com.webadmin.domain.iam.exception.TenantQuotaExceededException;
import com.webadmin.domain.shared.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 租户聚合根单元测试。
 *
 * <p>这是设计文档 §13.2 要求的「<b>领域模型必须 100% 覆盖</b>」的落点：
 * 聚合的不变量、状态机、配额规则都必须在无框架环境下可测 ——
 * 而这正是「领域层零框架依赖」的直接收益（本测试无需 Spring 上下文，毫秒级执行）。
 *
 * <p>注意每个测试都注入固定 {@link Clock}，因此时间相关行为完全确定。
 */
class TenantTest {

    /** 固定时钟：2026-09-14T00:00:00Z。 */
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final SubscriptionPlan PRO_PLAN = SubscriptionPlan.of(
            "PRO", "专业版", Quota.of(100L, 1024L, 10_000L));

    private static final SubscriptionPlan ENTERPRISE_PLAN = SubscriptionPlan.of(
            "ENTERPRISE", "企业版", Quota.of(10_000L, 1024L * 1024L, 1_000_000L));

    private static Tenant newTenant() {
        return Tenant.create(TenantId.of(1001L), TenantCode.of("acme-corp"),
                TenantName.of("Acme 科技"), PRO_PLAN, FIXED_CLOCK);
    }

    // ==================================================================
    // 创建
    // ==================================================================

    @Nested
    @DisplayName("创建租户")
    class Create {

        @Test
        @DisplayName("新建租户状态应为 PENDING 且产生 Created 事件")
        void should_create_pending_tenant_with_event() {
            Tenant tenant = newTenant();

            assertThat(tenant.status()).isEqualTo(TenantStatus.PENDING);
            assertThat(tenant.plan()).isEqualTo(PRO_PLAN);
            assertThat(tenant.quota()).isEqualTo(PRO_PLAN.initialQuota());
            assertThat(tenant.domainEvents())
                    .hasSize(1)
                    .first()
                    .isInstanceOf(TenantEvent.Created.class);
        }

        @Test
        @DisplayName("创建即赠送试用期，到期时间应晚于当前时刻")
        void should_grant_trial_period() {
            Tenant tenant = newTenant();
            assertThat(tenant.expireTime().isAfter(NOW)).isTrue();
        }

        @Test
        @DisplayName("PENDING 状态不可访问")
        void pending_tenant_should_not_be_accessible() {
            Tenant tenant = newTenant();
            assertThat(tenant.isAccessible(NOW)).isFalse();

            assertThatThrownBy(() -> tenant.assertAccessible(FIXED_CLOCK))
                    .isInstanceOf(IllegalTenantStateException.class);
        }

        @Test
        @DisplayName("reconstitute 不应产生领域事件（从库里读出来不是业务动作）")
        void reconstitute_should_not_emit_events() {
            Tenant tenant = Tenant.reconstitute(
                    TenantId.of(1001L), TenantCode.of("acme-corp"), TenantName.of("Acme"),
                    TenantStatus.ACTIVE, PRO_PLAN, PRO_PLAN.initialQuota(),
                    ExpireTime.now(FIXED_CLOCK).plusMonths(12));

            assertThat(tenant.domainEvents()).isEmpty();
        }
    }

    // ==================================================================
    // 状态机
    // ==================================================================

    @Nested
    @DisplayName("状态流转")
    class StateMachine {

        @Test
        @DisplayName("激活后状态为 ACTIVE，且可访问")
        void should_activate() {
            Tenant tenant = newTenant();
            tenant.pullDomainEvents();

            tenant.activate(FIXED_CLOCK);

            assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(tenant.isAccessible(NOW)).isTrue();
            assertThat(tenant.domainEvents()).hasSize(1)
                    .first().isInstanceOf(TenantEvent.Activated.class);
        }

        @Test
        @DisplayName("暂停需要原因，且暂停后不可访问")
        void should_suspend_with_reason() {
            Tenant tenant = newTenant();
            tenant.activate(FIXED_CLOCK);
            tenant.pullDomainEvents();

            tenant.suspend("欠费", FIXED_CLOCK);

            assertThat(tenant.status()).isEqualTo(TenantStatus.SUSPENDED);
            assertThat(tenant.isAccessible(NOW)).isFalse();
            assertThat(tenant.domainEvents()).hasSize(1)
                    .first().isInstanceOf(TenantEvent.Suspended.class);
        }

        @Test
        @DisplayName("暂停原因为空应被拒绝")
        void should_reject_blank_suspend_reason() {
            Tenant tenant = newTenant();
            tenant.activate(FIXED_CLOCK);

            assertThatThrownBy(() -> tenant.suspend("   ", FIXED_CLOCK))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("非法流转必须抛异常：CLOSED 是终态，不可再激活")
        void should_reject_transition_from_closed() {
            Tenant tenant = newTenant();
            tenant.activate(FIXED_CLOCK);
            tenant.close("客户注销", FIXED_CLOCK);

            assertThat(tenant.status()).isEqualTo(TenantStatus.CLOSED);

            assertThatThrownBy(() -> tenant.activate(FIXED_CLOCK))
                    .isInstanceOf(IllegalTenantStateException.class)
                    .hasMessageContaining("CLOSED");
        }

        @Test
        @DisplayName("非法流转必须抛异常：PENDING 不能直接暂停")
        void should_reject_pending_to_suspended() {
            Tenant tenant = newTenant();

            assertThatThrownBy(() -> tenant.suspend("测试", FIXED_CLOCK))
                    .isInstanceOf(IllegalTenantStateException.class);
        }

        @Test
        @DisplayName("状态枚举的状态机本身应正确裁决各条边")
        void status_enum_should_encode_transitions() {
            assertThat(TenantStatus.PENDING.canTransitTo(TenantStatus.ACTIVE)).isTrue();
            assertThat(TenantStatus.ACTIVE.canTransitTo(TenantStatus.SUSPENDED)).isTrue();
            assertThat(TenantStatus.EXPIRED.canTransitTo(TenantStatus.ACTIVE)).isTrue();
            assertThat(TenantStatus.CLOSED.canTransitTo(TenantStatus.ACTIVE)).isFalse();
            assertThat(TenantStatus.CLOSED.isTerminal()).isTrue();
        }
    }

    // ==================================================================
    // 续期
    // ==================================================================

    @Nested
    @DisplayName("续期")
    class Renew {

        @Test
        @DisplayName("续期应延长到期时间、切换套餐并重置配额")
        void should_renew_and_switch_plan() {
            Tenant tenant = newTenant();
            Instant originalExpire = tenant.expireTime().value();
            tenant.pullDomainEvents();

            tenant.renew(12, ENTERPRISE_PLAN, FIXED_CLOCK);

            assertThat(tenant.plan()).isEqualTo(ENTERPRISE_PLAN);
            assertThat(tenant.quota()).isEqualTo(ENTERPRISE_PLAN.initialQuota());
            assertThat(tenant.expireTime().value()).isAfter(originalExpire);
            assertThat(tenant.domainEvents()).hasSize(1)
                    .first().isInstanceOf(TenantEvent.Renewed.class);
        }

        @Test
        @DisplayName("未到期的租户应从原到期日顺延，不损失剩余时长")
        void should_extend_from_existing_expire_time_for_active_tenant() {
            Tenant tenant = newTenant();
            tenant.activate(FIXED_CLOCK);
            Instant originalExpire = tenant.expireTime().value();

            tenant.renew(1, PRO_PLAN, FIXED_CLOCK);

            // 原到期日 + 1 个月，而不是「当前时间 + 1 个月」
            assertThat(tenant.expireTime().value())
                    .isEqualTo(originalExpire.atZone(ZoneOffset.UTC).plusMonths(1).toInstant());
        }

        @Test
        @DisplayName("已过期租户续期应自动恢复为 ACTIVE")
        void should_reactivate_expired_tenant_on_renewal() {
            Tenant tenant = Tenant.reconstitute(
                    TenantId.of(1001L), TenantCode.of("acme-corp"), TenantName.of("Acme"),
                    TenantStatus.EXPIRED, PRO_PLAN, PRO_PLAN.initialQuota(),
                    ExpireTime.of(NOW.minus(Duration.ofDays(10))));

            tenant.renew(1, PRO_PLAN, FIXED_CLOCK);

            assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(tenant.isAccessible(NOW)).isTrue();
        }

        @Test
        @DisplayName("续期月数必须为正")
        void should_reject_non_positive_months() {
            Tenant tenant = newTenant();
            assertThatThrownBy(() -> tenant.renew(0, PRO_PLAN, FIXED_CLOCK))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ==================================================================
    // 过期语义
    // ==================================================================

    @Nested
    @DisplayName("过期判断")
    class Expiration {

        @Test
        @DisplayName("状态仍为 ACTIVE 但已过期时，有效状态应计算为 EXPIRED")
        void effective_status_should_report_expired() {
            Tenant tenant = Tenant.reconstitute(
                    TenantId.of(1001L), TenantCode.of("acme-corp"), TenantName.of("Acme"),
                    TenantStatus.ACTIVE, PRO_PLAN, PRO_PLAN.initialQuota(),
                    ExpireTime.of(NOW.minus(Duration.ofDays(1))));

            // 定时任务可能还没跑到，但业务上已不可用 —— 这正是 effectiveStatus 要解决的问题
            assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
            assertThat(tenant.effectiveStatus(NOW)).isEqualTo(TenantStatus.EXPIRED);
            assertThat(tenant.isAccessible(NOW)).isFalse();
        }
    }

    // ==================================================================
    // 配额
    // ==================================================================

    @Nested
    @DisplayName("配额")
    class QuotaRules {

        @Test
        @DisplayName("扣减配额应减少对应维度，且不影响其他维度")
        void should_consume_only_target_dimension() {
            Tenant tenant = newTenant();
            tenant.activate(FIXED_CLOCK);

            tenant.consumeQuota(QuotaType.USER, 10L);

            assertThat(tenant.quota().remaining(QuotaType.USER)).isEqualTo(90L);
            assertThat(tenant.quota().remaining(QuotaType.STORAGE_BYTES)).isEqualTo(1024L);
            assertThat(tenant.quota().remaining(QuotaType.API_CALLS_PER_MONTH)).isEqualTo(10_000L);
        }

        @Test
        @DisplayName("配额不足必须抛异常，调用方无需自行判断")
        void should_reject_when_quota_insufficient() {
            Tenant tenant = newTenant();
            tenant.activate(FIXED_CLOCK);

            assertThatThrownBy(() -> tenant.consumeQuota(QuotaType.USER, 999L))
                    .isInstanceOf(TenantQuotaExceededException.class);
        }

        @Test
        @DisplayName("值对象不可变：consume 返回新实例，原实例不受影响")
        void quota_should_be_immutable() {
            Quota original = Quota.of(10L, 100L, 1000L);
            Quota afterConsume = original.consume(QuotaType.USER, 3L);

            assertThat(original.remainingUsers()).isEqualTo(10L);
            assertThat(afterConsume.remainingUsers()).isEqualTo(7L);
        }

        @Test
        @DisplayName("暂停中的租户不允许扣减配额")
        void should_reject_consume_when_suspended() {
            Tenant tenant = newTenant();
            tenant.activate(FIXED_CLOCK);
            tenant.suspend("欠费", FIXED_CLOCK);

            assertThatThrownBy(() -> tenant.consumeQuota(QuotaType.USER, 1L))
                    .isInstanceOf(IllegalTenantStateException.class);
        }
    }

    // ==================================================================
    // 值对象不变量
    // ==================================================================

    @Nested
    @DisplayName("值对象校验")
    class ValueObjects {

        @Test
        @DisplayName("租户编码必须满足长度与字符集约束")
        void tenant_code_should_enforce_invariants() {
            assertThatThrownBy(() -> TenantCode.of("abc")).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> TenantCode.of("ABC-CORP")).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> TenantCode.of("-acme-corp")).isInstanceOf(RuntimeException.class);
            assertThat(TenantCode.of("acme-corp").value()).isEqualTo("acme-corp");
        }

        @Test
        @DisplayName("租户名称应去除首尾空白并校验长度")
        void tenant_name_should_trim_and_validate() {
            assertThat(TenantName.of("  Acme  ").value()).isEqualTo("Acme");
            assertThatThrownBy(() -> TenantName.of("A")).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("租户 ID 不允许为 0（0 保留给平台级）")
        void tenant_id_should_reject_zero() {
            assertThatThrownBy(() -> TenantId.of(0L)).isInstanceOf(IllegalArgumentException.class);
            assertThat(TenantId.ofPersisted(0L).isPlatform()).isTrue();
        }

        @Test
        @DisplayName("pullDomainEvents 应清空已取出的事件，避免重复发布")
        void pull_events_should_clear_buffer() {
            Tenant tenant = newTenant();

            assertThat(tenant.pullDomainEvents()).hasSize(1);
            assertThat(tenant.pullDomainEvents()).isEmpty();
        }
    }
}
