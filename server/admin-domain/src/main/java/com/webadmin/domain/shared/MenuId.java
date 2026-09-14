package com.webadmin.domain.shared;

/**
 * 菜单标识（值对象）。
 *
 * <p>注意：{@code 0} 是合法的 —— 它表示"根节点的父级"。
 * 因此这里的校验是「非负」而非「正数」，与 {@link UserId} 不同。
 * <b>约束必须跟着语义走，不能所有 ID 都套同一个模板。</b>
 */
public record MenuId(long value) {

    public MenuId {
        if (value < 0) {
            throw new IllegalArgumentException("菜单 ID 不能为负数，实际为: " + value);
        }
    }

    /** 根节点：{@code parent_id = 0}。 */
    public static final MenuId ROOT = new MenuId(0L);

    public static MenuId of(long value) {
        return new MenuId(value);
    }

    public boolean isRoot() {
        return value == 0L;
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
