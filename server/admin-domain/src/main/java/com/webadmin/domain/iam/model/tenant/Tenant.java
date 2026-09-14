package com.webadmin.domain.iam.model.tenant;

import com.webadmin.domain.iam.event.TenantEvent;
import com.webadmin.domain.iam.exception.IllegalTenantStateException;
import com.webadmin.domain.shared.DomainEvent;
import com.webadmin.domain.shared.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 租户聚合根。
 *
 * <h3>设计要点（设计文档 §4.5）</h3>
 * <ul>
 *   <li><b>零框架注解</b> —— 没有 {@code @TableName} / {@code @Entity} / {@code @Service}。
 *       持久化由 infrastructure 层的 {@code TenantPO} 承担，二者通过 MapStruct 转换</li>
 *   <li><b>不变量内聚</b> —— 状态流转、配额扣减、到期判断都在这里，AppService 只做编排</li>
 *   <li><b>产出领域事件</b> —— 每个业务动作登记事件，由 AppService 统一发布</li>
 *   <li><b>时间由 {@link Clock} 注入</b> —— 禁止 {@code Instant.now()}，否则领域逻辑无法确定性测试</li>
 *   <li><b>无 setter</b> —— 所有变更必须经过表达业务意图的方法</li>
 * </ul>
 *
 * <h3>为什么没有 delete()</h3>
 * 租户不做物理删除。生命周期终点是 {@link TenantStatus#CLOSED}（终态），
 * 保证历史数据的租户归属不会变成悬空引用（审计与合规要求）。
 */
public class Tenant {

    /** 新建租户默认赠送的试用月数。 */
    private static final int DEFAULT_TRIAL_MONTHS = 1;

    private final TenantId id;

    /** 创建后不可修改 —— 它是租户在所有系统中的稳定标识。 */
    private final TenantCode code;

    private TenantName name;
    private TenantStatus status;
    private SubscriptionPlan plan;
    private Quota quota;
    private ExpireTime expireTime;

    private final List<DomainEvent> domainEvents = new ArrayList<>();

    private Tenant(TenantId id,
                   TenantCode code,
                   TenantName name,
                   TenantStatus status,
                   SubscriptionPlan plan,
                   Quota quota,
                   ExpireTime expireTime) {
        this.id = Objects.requireNonNull(id, "id 不能为空");
        this.code = Objects.requireNonNull(code, "code 不能为空");
        this.name = Objects.requireNonNull(name, "name 不能为空");
        this.status = Objects.requireNonNull(status, "status 不能为空");
        this.plan = Objects.requireNonNull(plan, "plan 不能为空");
        this.quota = Objects.requireNonNull(quota, "quota 不能为空");
        this.expireTime = Objects.requireNonNull(expireTime, "expireTime 不能为空");
    }

    // ------------------------------------------------------------------
    // 工厂方法
    // ------------------------------------------------------------------

    /**
     * 创建新租户。
     *
     * <p>起始状态为 {@link TenantStatus#PENDING} —— 必须显式调用 {@link #activate} 才能使用。
     * 这是刻意的：租户初始化（建管理员、初始化字典、开通套餐）失败时不应留下「看起来可用」的租户。
     */
    public static Tenant create(TenantId id,
                                TenantCode code,
                                TenantName name,
                                SubscriptionPlan plan,
                                Clock clock) {
        Tenant tenant = new Tenant(
                id,
                code,
                name,
                TenantStatus.PENDING,
                plan,
                plan.initialQuota(),
                ExpireTime.now(clock).plusMonths(DEFAULT_TRIAL_MONTHS));
        tenant.register(new TenantEvent.Created(id, code.value(), plan.code(), clock.instant()));
        return tenant;
    }

    /**
     * 从持久化数据还原聚合。
     *
     * <p>与 {@link #create} 的关键区别：<b>不产生领域事件</b>。
     * 从数据库读出来的对象不是「发生了业务动作」，不应触发缓存失效或通知。
     *
     * <p>仅供 infrastructure 层的 Converter 调用。
     */
    public static Tenant reconstitute(TenantId id,
                                     TenantCode code,
                                     TenantName name,
                                     TenantStatus status,
                                     SubscriptionPlan plan,
                                     Quota quota,
                                     ExpireTime expireTime) {
        return new Tenant(id, code, name, status, plan, quota, expireTime);
    }

    // ------------------------------------------------------------------
    // 业务行为
    // ------------------------------------------------------------------

    /** 激活租户：PENDING → ACTIVE，或从 SUSPENDED / EXPIRED 恢复。 */
    public void activate(Clock clock) {
        status.assertCanTransitTo(TenantStatus.ACTIVE);
        this.status = TenantStatus.ACTIVE;
        register(new TenantEvent.Activated(id, code.value(), clock.instant()));
    }

    /**
     * 暂停租户：ACTIVE → SUSPENDED。
     *
     * <p>数据保留，但拒绝访问。订阅方需要清理权限缓存并踢下线。
     */
    public void suspend(String reason, Clock clock) {
        requireNonBlank(reason, "暂停原因");
        status.assertCanTransitTo(TenantStatus.SUSPENDED);
        this.status = TenantStatus.SUSPENDED;
        register(new TenantEvent.Suspended(id, code.value(), reason.trim(), clock.instant()));
    }

    /**
     * 续期：延长到期时间、切换套餐、重置配额。
     *
     * <p>若当前为 EXPIRED，续期会自动恢复到 ACTIVE —— 这是「续费即恢复」的业务语义。
     */
    public void renew(int months, SubscriptionPlan newPlan, Clock clock) {
        if (months <= 0) {
            throw new IllegalArgumentException("续期月数必须为正数，实际为: " + months);
        }
        Objects.requireNonNull(newPlan, "套餐不能为空");

        // 未到期的租户从原到期日顺延，避免续费导致用户损失剩余时长
        ExpireTime base = expireTime.isExpired(clock.instant()) ? ExpireTime.now(clock) : expireTime;
        this.expireTime = base.plus(Period.ofMonths(months));
        this.plan = newPlan;
        this.quota = newPlan.initialQuota();

        if (status == TenantStatus.EXPIRED) {
            this.status = TenantStatus.ACTIVE;
        }

        register(new TenantEvent.Renewed(
                id, code.value(), newPlan.code(), expireTime.value(), clock.instant()));
    }

    /**
     * 标记为已过期。
     *
     * <p>由定时任务批量推进（ACTIVE → EXPIRED），业务代码不应直接调用。
     */
    public void markExpired(Clock clock) {
        if (status != TenantStatus.ACTIVE) {
            throw new IllegalTenantStateException(status, "markExpired");
        }
        this.status = TenantStatus.EXPIRED;
        register(new TenantEvent.Expired(id, code.value(), clock.instant()));
    }

    /** 关闭租户（终态，不可恢复）。 */
    public void close(String reason, Clock clock) {
        requireNonBlank(reason, "关闭原因");
        status.assertCanTransitTo(TenantStatus.CLOSED);
        this.status = TenantStatus.CLOSED;
        register(new TenantEvent.Closed(id, code.value(), reason.trim(), clock.instant()));
    }

    /** 改名（仅展示信息，不影响标识）。 */
    public void rename(TenantName newName, Clock clock) {
        Objects.requireNonNull(newName, "新名称不能为空");
        if (status.isTerminal()) {
            throw new IllegalTenantStateException(status, "rename");
        }
        if (this.name.equals(newName)) {
            return;
        }
        this.name = newName;
        register(new TenantEvent.Renamed(id, code.value(), newName.value(), clock.instant()));
    }

    /**
     * 扣减配额。
     *
     * <p>不足时抛出 {@code TenantQuotaExceededException}，调用方无需自行判断。
     */
    public void consumeQuota(QuotaType type, long amount) {
        if (status.isTerminal() || status == TenantStatus.SUSPENDED) {
            throw new IllegalTenantStateException(status, "consumeQuota");
        }
        this.quota = quota.consume(type, amount);
    }

    // 说明：这里刻意<b>不提供</b> resetQuota() 方法。
    // 初看它很合理（月度 API 调用量归零），但存在一个隐蔽缺陷：
    // 从持久化还原时，plan 只由 planCode/planName 两列重建，其 initialQuota 是从
    // 当前的 remaining_* 列推导出来的「剩余量」而非套餐的真实初始配额。
    // 若基于它重置，配额会越用越少 —— 而且不报错。
    // 正确做法：由应用层从 SubscriptionPlanRegistry 取回真实套餐，
    // 再通过 renew(...) 或专门的 changePlan(SubscriptionPlan, Clock) 应用。

    // ------------------------------------------------------------------
    // 查询语义
    // ------------------------------------------------------------------

    /**
     * 在指定时刻是否可访问。
     *
     * <p>把「状态」与「是否过期」两个条件合并为一个领域判断，
     * 避免调用方到处写 {@code status == ACTIVE && !expired}（很容易漏掉一个）。
     */
    public boolean isAccessible(Instant now) {
        return status.isAccessible() && !expireTime.isExpired(now);
    }

    /**
     * 计算在指定时刻的<b>有效状态</b>。
     *
     * <p>用于解决「状态字段还是 ACTIVE，但已经过期」的脏读问题：
     * 定时任务可能还没跑到，但业务上该租户已经不可用。
     */
    public TenantStatus effectiveStatus(Instant now) {
        if (status == TenantStatus.ACTIVE && expireTime.isExpired(now)) {
            return TenantStatus.EXPIRED;
        }
        return status;
    }

    /** 断言可访问，否则抛出对应领域异常。 */
    public void assertAccessible(Clock clock) {
        Instant now = clock.instant();
        if (expireTime.isExpired(now)) {
            throw new com.webadmin.domain.iam.exception.IllegalTenantStateException(
                    effectiveStatus(now), "访问（租户已过期）");
        }
        if (!status.isAccessible()) {
            throw new IllegalTenantStateException(status, "访问");
        }
    }

    // ------------------------------------------------------------------
    // 领域事件
    // ------------------------------------------------------------------

    private void register(DomainEvent event) {
        this.domainEvents.add(event);
    }

    /**
     * 取出并清空累积的领域事件。
     *
     * <p>由 AppService 在持久化成功前调用并发布。返回不可变视图，防止外部误加事件。
     */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    /** 只读查看（测试与断言用，不清空）。 */
    public List<DomainEvent> domainEvents() {
        return Collections.unmodifiableList(domainEvents);
    }

    // ------------------------------------------------------------------
    // 访问器（无 setter）
    // ------------------------------------------------------------------

    public TenantId id() {
        return id;
    }

    public TenantCode code() {
        return code;
    }

    public TenantName name() {
        return name;
    }

    public TenantStatus status() {
        return status;
    }

    public SubscriptionPlan plan() {
        return plan;
    }

    public Quota quota() {
        return quota;
    }

    public ExpireTime expireTime() {
        return expireTime;
    }

    private static void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
    }

    /**
     * 聚合相等性基于标识：两个 Tenant 对象只要 ID 相同就是同一个聚合。
     * 这与实体（Entity）的 DDD 语义一致，也是持久化层判断 insert/update 的依据。
     */
    @Override
    public boolean equals(Object o) {
        return o instanceof Tenant other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Tenant{id=" + id + ", code=" + code + ", status=" + status + "}";
    }
}
