package com.webadmin.application.iam.port;

import com.webadmin.domain.iam.model.tenant.SubscriptionPlan;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import java.util.List;
import java.util.Optional;

/**
 * 套餐注册表端口。
 *
 * <p>应用层通过本端口解析套餐，避免调用方（HTTP 请求体）直接伪造配额。
 *
 * <p>实现方在 infrastructure 层。当前 P0 阶段用内置枚举实现（见
 * {@code InMemorySubscriptionPlanRegistry}），后续接入 {@code iam_tenant_package} 表
 * 与计费上下文后替换实现即可 —— 这正是端口隔离的价值。
 */
public interface SubscriptionPlanRegistry {

    Optional<SubscriptionPlan> findByCode(String planCode);

    List<SubscriptionPlan> findAll();

    /** 按编码解析套餐，不存在则抛出业务异常。 */
    default SubscriptionPlan requireByCode(String planCode) {
        return findByCode(planCode)
                .orElseThrow(() -> new BizException(
                        CommonErrorCode.PARAM_INVALID, "套餐不存在: " + planCode));
    }
}
