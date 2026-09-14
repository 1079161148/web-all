package com.webadmin.domain.iam.exception;

import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.shared.DomainException;

/** 租户到期时间不合法。 */
public class TenantExpireTimeInvalidException extends DomainException {

    public TenantExpireTimeInvalidException(String detail) {
        super(IamErrorCode.TENANT_EXPIRE_TIME_INVALID, detail);
    }
}
