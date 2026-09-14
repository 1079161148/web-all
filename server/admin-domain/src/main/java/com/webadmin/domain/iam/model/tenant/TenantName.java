package com.webadmin.domain.iam.model.tenant;

import com.webadmin.domain.iam.exception.TenantNameInvalidException;

/**
 * 租户名称（值对象）。
 *
 * <p>不变量：去除首尾空白后长度 2 ~ 64。
 */
public record TenantName(String value) {

    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 64;

    public TenantName {
        if (value == null || value.isBlank()) {
            throw new TenantNameInvalidException("不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() < MIN_LENGTH || normalized.length() > MAX_LENGTH) {
            throw new TenantNameInvalidException(
                    "长度需在 " + MIN_LENGTH + "~" + MAX_LENGTH + " 之间，实际为 " + normalized.length());
        }
        value = normalized;
    }

    public static TenantName of(String value) {
        return new TenantName(value);
    }

    public TenantName renameTo(String newValue) {
        return new TenantName(newValue);
    }

    @Override
    public String toString() {
        return value;
    }
}
