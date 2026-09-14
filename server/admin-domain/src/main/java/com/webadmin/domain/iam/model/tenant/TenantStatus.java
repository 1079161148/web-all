package com.webadmin.domain.iam.model.tenant;

import com.webadmin.domain.iam.exception.IllegalTenantStateException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 租户状态（值对象，内含状态机）。
 *
 * <p>设计要点（设计文档 §4.5）：<b>状态流转合法性由本枚举内置，而不是散落在 Service 的 if 里。</b>
 * 这样任何新增的调用路径都无法绕过规则。
 *
 * <pre>
 *   PENDING ──activate──▶ ACTIVE ──suspend──▶ SUSPENDED
 *      │                    │  ▲                  │
 *      │                    │  └─────activate─────┘
 *      │                    │
 *      │                    ├──expire──▶ EXPIRED ──activate(续费)──▶ ACTIVE
 *      │                    │
 *      └────────────────────┴──close──▶ CLOSED（终态，不可再流转）
 * </pre>
 */
public enum TenantStatus {

    /** 已创建，尚未激活（等待初始化或付费确认）。 */
    PENDING,

    /** 正常可用。 */
    ACTIVE,

    /** 已暂停（如欠费、违规）。数据保留，但拒绝访问。 */
    SUSPENDED,

    /** 已过期（超出 expireTime）。可通过续费回到 ACTIVE。 */
    EXPIRED,

    /** 已关闭（终态）。不可恢复。 */
    CLOSED;

    private static final Map<TenantStatus, Set<TenantStatus>> ALLOWED_TRANSITIONS = Map.of(
            PENDING, EnumSet.of(ACTIVE, CLOSED),
            ACTIVE, EnumSet.of(SUSPENDED, EXPIRED, CLOSED),
            SUSPENDED, EnumSet.of(ACTIVE, CLOSED),
            EXPIRED, EnumSet.of(ACTIVE, CLOSED),
            CLOSED, EnumSet.noneOf(TenantStatus.class)
    );

    /** 是否允许流转到目标状态。 */
    public boolean canTransitTo(TenantStatus target) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /**
     * 断言允许流转，否则抛出领域异常。
     *
     * <p>聚合根的所有状态变更都必须先经过本方法，避免遗漏校验。
     */
    public void assertCanTransitTo(TenantStatus target) {
        if (!canTransitTo(target)) {
            throw new IllegalTenantStateException(this, target);
        }
    }

    /** 是否可被访问（可登录 / 可调用接口）。 */
    public boolean isAccessible() {
        return this == ACTIVE;
    }

    /** 是否为终态。 */
    public boolean isTerminal() {
        return this == CLOSED;
    }

    /** 是否为需要保留数据的停用态（区别于终态）。 */
    public boolean isSuspendedLike() {
        return this == SUSPENDED || this == EXPIRED;
    }
}
