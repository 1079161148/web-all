package com.webadmin.domain.shared;

/** 角色标识（值对象）。 */
public record RoleId(long value) {

    public RoleId {
        if (value <= 0) {
            throw new IllegalArgumentException("角色 ID 必须为正数，实际为: " + value);
        }
    }

    public static RoleId of(long value) {
        return new RoleId(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
