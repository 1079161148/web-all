package com.webadmin.domain.iam.exception;

import com.webadmin.common.error.ErrorCode;
import com.webadmin.domain.iam.IamErrorCode;
import com.webadmin.domain.shared.DomainException;

/**
 * 角色状态非法（含内置角色保护、非法修改）。
 *
 * <p>与 {@code IllegalUserStateException} 分开而不是共用一个"状态异常"，
 * 是因为二者的<b>错误码不同</b>。前端需要据此给出不同引导：
 * 用户状态异常通常提示"联系管理员"，而内置角色受限应提示"这是系统内置角色，无法修改"。
 * 把语义不同的拒绝塞进同一个错误码，会让前端只能靠解析文案来区分 —— 那很脆弱。
 */
public class IllegalRoleStateException extends DomainException {

    /** 默认错误码：{@code ROLE_ILLEGAL_STATE}。 */
    public IllegalRoleStateException(String detail) {
        super(IamErrorCode.ROLE_ILLEGAL_STATE, detail);
    }

    /** 允许指定更精确的错误码（如内置角色保护用 {@code BUILTIN_ROLE_IMMUTABLE}）。 */
    public IllegalRoleStateException(ErrorCode errorCode, String detail) {
        super(errorCode, detail);
    }

    /** 内置角色被修改/删除：错误码更精确，前端可据此给出精准提示。 */
    public static IllegalRoleStateException builtinImmutable(String roleName) {
        return new IllegalRoleStateException(
                IamErrorCode.BUILTIN_ROLE_IMMUTABLE,
                "内置角色不允许修改或删除：" + roleName);
    }
}
