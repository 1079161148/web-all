package com.webadmin.domain.iam.exception;

import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.shared.DomainException;

/** 租户编码不满足不变量。 */
public class TenantCodeInvalidException extends DomainException {

    public TenantCodeInvalidException(String detail) {
        super(IamErrorCode.TENANT_CODE_INVALID, detail);
    }
}
