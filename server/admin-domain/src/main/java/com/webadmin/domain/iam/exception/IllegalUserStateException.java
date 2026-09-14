package com.webadmin.domain.iam.exception;

import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.shared.DomainException;

/**
 * 用户状态非法（含登录被拒、非法状态流转）。
 *
 * <p>构造器只接受"细节描述"，错误码固定为 {@code USER_ILLEGAL_STATE} ——
 * 调用方不需要、也不应该在每次抛异常时纠结"该用哪个错误码"。
 * 错误码是<b>这一类问题</b>的标识，细节描述才是每次不同的部分。
 */
public class IllegalUserStateException extends DomainException {

    public IllegalUserStateException(String detail) {
        super(IamErrorCode.USER_ILLEGAL_STATE, detail);
    }
}
