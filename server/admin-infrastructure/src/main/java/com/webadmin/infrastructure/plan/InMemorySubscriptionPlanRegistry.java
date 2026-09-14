package com.webadmin.infrastructure.plan;

import com.webadmin.application.iam.port.SubscriptionPlanRegistry;
import com.webadmin.domain.iam.model.tenant.Quota;
import com.webadmin.domain.iam.model.tenant.SubscriptionPlan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 套餐注册表的内存实现。
 *
 * <p>⚠️ <b>这是 P0 骨架的占位实现</b>。按设计文档 §9.4，套餐最终应落在
 * {@code iam_tenant_package} 表，并与计费上下文联动（价格、功能开关、按量计费）。
 *
 * <p>之所以先做成内存实现而不是直接建表：套餐的完整模型属于计费上下文，
 * 在 P0 阶段过早定型会带来返工。<b>端口已隔离，替换实现不影响任何调用方</b> ——
 * 这正是设计文档 §4.3 依赖倒置的价值。
 */
@Component
public class InMemorySubscriptionPlanRegistry implements SubscriptionPlanRegistry {

    private static final long GB = 1024L * 1024L * 1024L;

    private final Map<String, SubscriptionPlan> plans = new LinkedHashMap<>();

    public InMemorySubscriptionPlanRegistry() {
        register(SubscriptionPlan.of("FREE", "免费版",
                Quota.of(10L, 1L * GB, 10_000L)));
        register(SubscriptionPlan.of("PRO", "专业版",
                Quota.of(100L, 100L * GB, 1_000_000L)));
        register(SubscriptionPlan.of("ENTERPRISE", "企业版",
                Quota.of(10_000L, 1024L * GB, 100_000_000L)));
    }

    private void register(SubscriptionPlan plan) {
        plans.put(plan.code(), plan);
    }

    @Override
    public Optional<SubscriptionPlan> findByCode(String planCode) {
        if (planCode == null || planCode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(plans.get(planCode.trim().toUpperCase()));
    }

    @Override
    public List<SubscriptionPlan> findAll() {
        return List.copyOf(plans.values());
    }
}
