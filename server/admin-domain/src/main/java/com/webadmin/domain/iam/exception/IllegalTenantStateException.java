package com.webadmin.domain.iam.exception;

import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.iam.model.tenant.TenantStatus;
import com.webadmin.domain.shared.DomainException;

/**
 * 非法的租户状态流转。
 *
 * <p>这是「把规则放进聚合」后最典型的一类领域异常：任何调用路径试图做非法流转都会被拒绝，
 * 而不是靠调用方自觉判断。
 */
public class IllegalTenantStateException extends DomainException {

    public IllegalTenantStateException(TenantStatus from, TenantStatus to) {
        super(IamErrorCode.TENANT_ILLEGAL_STATE, "不允许从 " + from + " 流转到 " + to);
    }

    public IllegalTenantStateException(TenantStatus current, String action) {
        super(IamErrorCode.TENANT_ILLEGAL_STATE, "当前状态 " + current + " 不支持操作 [" + action + "]");
    }
}
