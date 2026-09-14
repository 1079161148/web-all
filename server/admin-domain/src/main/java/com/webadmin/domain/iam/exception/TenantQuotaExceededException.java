package com.webadmin.domain.iam.exception;

import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.iam.model.tenant.QuotaType;
import com.webadmin.domain.shared.DomainException;

/** 租户配额不足。 */
public class TenantQuotaExceededException extends DomainException {

    public TenantQuotaExceededException(QuotaType type, long requested, long remaining) {
        super(IamErrorCode.TENANT_QUOTA_EXCEEDED,
                type.getDescription() + " 需要 " + requested + "，剩余 " + remaining);
    }
}
