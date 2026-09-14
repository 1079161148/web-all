package com.webadmin.domain.shared;

/**
 * 部门标识（值对象）。
 *
 * <p>与 {@link MenuId} 同理，{@code 0} 表示"根节点"，故校验为「非负」。
 */
public record DeptId(long value) {

    public DeptId {
        if (value < 0) {
            throw new IllegalArgumentException("部门 ID 不能为负数，实际为: " + value);
        }
    }

    /** 根节点：{@code parent_id = 0}。 */
    public static final DeptId ROOT = new DeptId(0L);

    public static DeptId of(long value) {
        return new DeptId(value);
    }

    public boolean isRoot() {
        return value == 0L;
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
