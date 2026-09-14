package com.webadmin.domain.iam.exception;

import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.shared.DomainException;

/** 租户名称不满足不变量。 */
public class TenantNameInvalidException extends DomainException {

    public TenantNameInvalidException(String detail) {
        super(IamErrorCode.TENANT_NAME_INVALID, detail);
    }
}
