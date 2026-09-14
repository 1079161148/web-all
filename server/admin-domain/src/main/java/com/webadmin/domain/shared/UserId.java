package com.webadmin.domain.shared;

/**
 * 用户标识（值对象）。
 *
 * <p>与 {@link TenantId} 同样是 record + 构造校验。这里不做 {@code of} /
 * {@code ofPersisted} 的区分：用户 ID 没有"平台级保留值"这种特殊语义，
 * 一个统一的「必须为正数」约束就够了 —— <b>不要为了对称而引入没有实际约束
 * 差异的工厂方法</b>，那只会让调用方多一层选择困难。
 */
public record UserId(long value) {

    public UserId {
        if (value <= 0) {
            throw new IllegalArgumentException("用户 ID 必须为正数，实际为: " + value);
        }
    }

    public static UserId of(long value) {
        return new UserId(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
