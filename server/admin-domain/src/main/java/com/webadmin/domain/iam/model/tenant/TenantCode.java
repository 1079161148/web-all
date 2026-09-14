package com.webadmin.domain.iam.model.tenant;

import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.iam.exception.TenantCodeInvalidException;
import java.util.regex.Pattern;

/**
 * 租户编码（值对象）。
 *
 * <p>不变量（设计文档 §4.5 值对象清单）：
 * <ul>
 *   <li>长度 6 ~ 32</li>
 *   <li>仅允许小写字母、数字、连字符</li>
 *   <li>必须以字母或数字开头与结尾</li>
 *   <li><b>创建后不可修改</b>（因此聚合根中本字段无 setter）</li>
 *   <li>全局唯一（该唯一性由仓储 + 数据库唯一索引共同保证，属于跨聚合一类规则）</li>
 * </ul>
 */
public record TenantCode(String value) {

    /** 总长度 6~32：首尾各 1 位字母数字 + 中间 4~30 位。 */
    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{4,30}[a-z0-9]$");

    private static final int MAX_LENGTH = 32;
    private static final int MIN_LENGTH = 6;

    public TenantCode {
        if (value == null || value.isBlank()) {
            throw new TenantCodeInvalidException("不能为空");
        }
        String normalized = value.trim();
        if (normalized.length() < MIN_LENGTH || normalized.length() > MAX_LENGTH) {
            throw new TenantCodeInvalidException(
                    "长度需在 " + MIN_LENGTH + "~" + MAX_LENGTH + " 之间，实际为 " + normalized.length());
        }
        if (!PATTERN.matcher(normalized).matches()) {
            throw new TenantCodeInvalidException(
                    "仅允许小写字母、数字与连字符，且首尾必须为字母或数字: " + normalized);
        }
        value = normalized;
    }

    public static TenantCode of(String value) {
        return new TenantCode(value);
    }

    /** 从持久化层还原，复用同一套校验（数据库中的数据也必须满足不变量）。 */
    public static TenantCode ofPersisted(String value) {
        return new TenantCode(value);
    }

    public IamErrorCode errorCode() {
        return IamErrorCode.TENANT_CODE_INVALID;
    }

    @Override
    public String toString() {
        return value;
    }
}
