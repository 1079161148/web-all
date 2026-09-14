package com.webadmin.domain.shared;

import com.webadmin.common.tenant.TenantContext;
import java.util.Objects;

/**
 * 租户标识（值对象）。
 *
 * <h3>不变量分层</h3>
 * <ul>
 *   <li><b>规范构造器</b>保证「非负」—— 任何途径构造出的实例都不会是负数</li>
 *   <li><b>{@link #of(long)}</b>（业务创建）额外要求「正数」，因为 {@code 0} 保留给平台级。
 *       调用方无法通过它建出平台级租户 ID</li>
 *   <li><b>{@link #ofPersisted(long)}</b>（持久化还原）允许 {@code 0} ——
 *       平台级数据必须能被读回来，这是「业务创建」与「数据还原」校验强度不同的合理场景</li>
 * </ul>
 *
 * <p><b>为什么不用额外的私有构造器做区分</b>：record 的非规范构造器必须以
 * {@code this(...)} 委托给规范构造器，无法像普通类那样直接赋值字段。
 * 用「规范构造器管底线 + 工厂方法管语义」既符合 record 的约束，
 * 也让两类校验的职责更清晰。
 */
public record TenantId(long value) {

    public TenantId {
        if (value < 0) {
            throw new IllegalArgumentException("租户 ID 不能为负数，实际为: " + value);
        }
    }

    /**
     * 业务创建租户时使用。
     *
     * @throws IllegalArgumentException 传入 {@code 0}（平台级保留值）或负数
     */
    public static TenantId of(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    "租户 ID 必须为正数（0 为平台级保留值），实际为: " + value);
        }
        return new TenantId(value);
    }

    /** 从持久化层还原，允许 {@code 0}（平台级数据）。 */
    public static TenantId ofPersisted(long value) {
        return new TenantId(value);
    }

    /** 是否为平台级（{@code 0}）。 */
    public boolean isPlatform() {
        return value == TenantContext.PLATFORM_TENANT_ID;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TenantId other && value == other.value;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
