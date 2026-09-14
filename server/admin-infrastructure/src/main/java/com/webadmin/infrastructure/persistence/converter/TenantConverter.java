package com.webadmin.infrastructure.persistence.converter;

import com.webadmin.domain.iam.model.tenant.ExpireTime;
import com.webadmin.domain.iam.model.tenant.Quota;
import com.webadmin.domain.iam.model.tenant.SubscriptionPlan;
import com.webadmin.domain.iam.model.tenant.Tenant;
import com.webadmin.domain.iam.model.tenant.TenantCode;
import com.webadmin.domain.iam.model.tenant.TenantName;
import com.webadmin.domain.shared.TenantId;
import com.webadmin.infrastructure.persistence.po.TenantPO;
import org.springframework.stereotype.Component;

/**
 * 聚合根 ↔ 持久化对象 转换器。
 *
 * <h3>为什么这里是手写而不是 MapStruct</h3>
 * MapStruct 适合字段平铺、一一对应的机械映射（例如 PO → DTO）。
 * 但聚合根的重建<b>不是机械映射</b>：
 * <ul>
 *   <li>需要把 3 个扁平列还原成 {@link Quota} 值对象（内含非负校验）</li>
 *   <li>需要把 2 个列还原成 {@link SubscriptionPlan} 值对象（内含编码大写化）</li>
 *   <li>需要把 {@code Instant} 包装成 {@link ExpireTime}（内含非空校验）</li>
 *   <li>必须调用 {@code Tenant.reconstitute(...)} 而不是 {@code create(...)}，
 *       以保证<b>不产生领域事件</b> —— 从库里读出来的对象不是「发生了业务动作」</li>
 * </ul>
 * 用 MapStruct 表达这些会退化成满是 {@code expression = "java(...)"} 的注解，
 * 可读性反而更差。<b>工具用在对的地方</b>：PO → DTO 的机械映射仍用 MapStruct
 * （见 {@code TenantPoMapper}）。
 */
@Component
public class TenantConverter {

    /** 聚合根 → PO（用于写入）。 */
    public TenantPO toPO(Tenant tenant) {
        TenantPO po = new TenantPO();
        po.setId(tenant.id().value());
        po.setCode(tenant.code().value());
        po.setName(tenant.name().value());
        po.setStatus(tenant.status());
        po.setPlanCode(tenant.plan().code());
        po.setPlanName(tenant.plan().name());
        po.setRemainingUsers(tenant.quota().remainingUsers());
        po.setRemainingStorageBytes(tenant.quota().remainingStorageBytes());
        po.setRemainingApiCalls(tenant.quota().remainingApiCalls());
        po.setExpireTime(tenant.expireTime().value());
        return po;
    }

    /**
     * PO → 聚合根（用于读取）。
     *
     * <p>走 {@code reconstitute} 而非 {@code create}，因此不会产生领域事件。
     * 这是「读操作不应触发副作用」在代码层面的落实。
     *
     * <p>⚠️ 注意：{@code iam_tenant} 只存了 {@code plan_code} / {@code plan_name}，
     * 因此还原出的 {@code SubscriptionPlan} 其 {@code initialQuota} 是由当前
     * {@code remaining_*} 推导的快照，<b>不代表套餐的真实初始配额</b>。
     * 任何需要「按套餐重置配额」的逻辑都必须先经 {@code SubscriptionPlanRegistry}
     * 取回真实套餐，不得依赖此处还原的对象。
     */
    public Tenant toDomain(TenantPO po) {
        if (po == null) {
            return null;
        }
        return Tenant.reconstitute(
                TenantId.ofPersisted(po.getId()),
                TenantCode.ofPersisted(po.getCode()),
                TenantName.of(po.getName()),
                po.getStatus(),
                SubscriptionPlan.of(
                        po.getPlanCode(),
                        po.getPlanName() == null ? po.getPlanCode() : po.getPlanName(),
                        Quota.of(
                                nullSafe(po.getRemainingUsers()),
                                nullSafe(po.getRemainingStorageBytes()),
                                nullSafe(po.getRemainingApiCalls()))),
                Quota.of(
                        nullSafe(po.getRemainingUsers()),
                        nullSafe(po.getRemainingStorageBytes()),
                        nullSafe(po.getRemainingApiCalls())),
                ExpireTime.of(po.getExpireTime()));
    }

    /**
     * 把聚合根的可变状态回填到已存在的 PO 上。
     *
     * <p>用于更新场景：保留 PO 上的 {@code version} / 审计字段，
     * 只覆盖业务字段与配额，避免把 {@code version} 覆盖成 null 导致乐观锁失效。
     */
    public void mergeIntoPO(Tenant tenant, TenantPO po) {
        po.setName(tenant.name().value());
        po.setStatus(tenant.status());
        po.setPlanCode(tenant.plan().code());
        po.setPlanName(tenant.plan().name());
        po.setRemainingUsers(tenant.quota().remainingUsers());
        po.setRemainingStorageBytes(tenant.quota().remainingStorageBytes());
        po.setRemainingApiCalls(tenant.quota().remainingApiCalls());
        po.setExpireTime(tenant.expireTime().value());
    }

    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }
}
