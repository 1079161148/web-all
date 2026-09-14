package com.webadmin.domain.iam.model.tenant;

import com.webadmin.domain.iam.exception.TenantQuotaExceededException;

/**
 * 租户配额（值对象，语义为「剩余可用量」）。
 *
 * <p>不变量：各维度剩余量均不得为负。
 *
 * <p>设计要点：扣减逻辑（含不足时的拒绝）内聚在这里，而不是散落在业务代码里做
 * {@code if (remaining < amount) throw ...}。这样任何调用方都无法绕过配额检查。
 *
 * <p>作为不可变值对象，{@link #consume} 返回新实例而非修改自身。
 */
public record Quota(long remainingUsers, long remainingStorageBytes, long remainingApiCalls) {

    public Quota {
        assertNonNegative(QuotaType.USER, remainingUsers);
        assertNonNegative(QuotaType.STORAGE_BYTES, remainingStorageBytes);
        assertNonNegative(QuotaType.API_CALLS_PER_MONTH, remainingApiCalls);
    }

    /** 套餐初始配额。 */
    public static Quota of(long users, long storageBytes, long apiCallsPerMonth) {
        return new Quota(users, storageBytes, apiCallsPerMonth);
    }

    /** 查询某一维度的剩余量。 */
    public long remaining(QuotaType type) {
        return switch (type) {
            case USER -> remainingUsers;
            case STORAGE_BYTES -> remainingStorageBytes;
            case API_CALLS_PER_MONTH -> remainingApiCalls;
        };
    }

    /**
     * 扣减配额，返回新的配额实例。
     *
     * @throws TenantQuotaExceededException 剩余量不足时抛出，调用方无需自行判断
     */
    public Quota consume(QuotaType type, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("配额扣减量不能为负数，实际为: " + amount);
        }
        long remaining = remaining(type);
        if (remaining < amount) {
            throw new TenantQuotaExceededException(type, amount, remaining);
        }
        return switch (type) {
            case USER -> new Quota(remainingUsers - amount, remainingStorageBytes, remainingApiCalls);
            case STORAGE_BYTES -> new Quota(remainingUsers, remainingStorageBytes - amount, remainingApiCalls);
            case API_CALLS_PER_MONTH -> new Quota(remainingUsers, remainingStorageBytes, remainingApiCalls - amount);
        };
    }

    /** 是否已耗尽某一维度。 */
    public boolean isExhausted(QuotaType type) {
        return remaining(type) <= 0;
    }

    private static void assertNonNegative(QuotaType type, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "配额 [" + type.getDescription() + "] 不能为负数，实际为: " + value);
        }
    }
}
