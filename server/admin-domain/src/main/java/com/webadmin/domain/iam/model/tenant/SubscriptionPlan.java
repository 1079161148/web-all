package com.webadmin.domain.iam.model.tenant;

/**
 * 租户套餐（值对象）。
 *
 * <p>决定租户生命周期起点与续费时可获得的初始配额。
 * 套餐的完整定义（价格、功能开关等）属于计费上下文，本对象只承载 IAM 需要的最小信息。
 *
 * @param code         套餐编码，如 {@code FREE} / {@code PRO} / {@code ENTERPRISE}
 * @param name         套餐名称（展示用）
 * @param initialQuota 该套餐授予的初始配额
 */
public record SubscriptionPlan(String code, String name, Quota initialQuota) {

    public SubscriptionPlan {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("套餐编码不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("套餐名称不能为空");
        }
        if (initialQuota == null) {
            throw new IllegalArgumentException("套餐必须定义初始配额");
        }
        code = code.trim().toUpperCase();
        name = name.trim();
    }

    public static SubscriptionPlan of(String code, String name, Quota initialQuota) {
        return new SubscriptionPlan(code, name, initialQuota);
    }
}
