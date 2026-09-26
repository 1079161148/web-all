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

    /**
     * 归还配额（业务对象被删除时调用），返回新实例。
     *
     * <h3>为什么必须提供归还</h3>
     * 只有扣减没有归还的配额是单向消耗：租户删掉一个用户后，
     * 那个名额就永久消失了 —— 表现为"明明删了人却还是建不了新用户"。
     * 用户会把它当成 bug（它确实是）。
     *
     * <p>注意本方法<b>不做上限检查</b>：归还可能使剩余量超过套餐初始值
     * （例如套餐已降级）。上限收敛是 {@code Tenant#releaseQuota} 的职责 ——
     * 只有聚合知道"当前套餐的初始配额"是多少。
     */
    public Quota release(QuotaType type, long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("配额归还量不能为负数，实际为: " + amount);
        }
        if (amount == 0) {
            return this;
        }
        return switch (type) {
            case USER -> new Quota(remainingUsers + amount, remainingStorageBytes, remainingApiCalls);
            case STORAGE_BYTES -> new Quota(remainingUsers, remainingStorageBytes + amount, remainingApiCalls);
            case API_CALLS_PER_MONTH -> new Quota(remainingUsers, remainingStorageBytes, remainingApiCalls + amount);
        };
    }

    private static void assertNonNegative(QuotaType type, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "配额 [" + type.getDescription() + "] 不能为负数，实际为: " + value);
        }
    }
}
