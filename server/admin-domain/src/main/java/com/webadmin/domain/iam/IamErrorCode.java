package com.webadmin.domain.iam;

import com.webadmin.common.error.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * IAM 限界上下文错误码（3xxxx）。
 *
 * <p>领域层持有自己的错误码，避免规则散落。任何新增的领域规则违反都应在此登记。
 *
 * <p>手写 {@code code()} / {@code message()} 的原因见
 * {@code CommonErrorCode} 的类注释（Lombok 生成的方法名与 {@link ErrorCode} 接口不匹配）。
 */
@RequiredArgsConstructor
public enum IamErrorCode implements ErrorCode {

    // ---- 3xxxx 租户 ----
    TENANT_NOT_FOUND(30001, "租户不存在"),
    TENANT_CODE_DUPLICATED(30002, "租户编码已存在"),
    TENANT_CODE_INVALID(30003, "租户编码格式不合法"),
    TENANT_CODE_IMMUTABLE(30004, "租户编码创建后不可修改"),
    TENANT_ILLEGAL_STATE(30005, "租户当前状态不允许该操作"),
    TENANT_QUOTA_EXCEEDED(30006, "租户配额不足"),
    TENANT_EXPIRED(30007, "租户已过期"),
    TENANT_NAME_INVALID(30008, "租户名称格式不合法"),
    TENANT_EXPIRE_TIME_INVALID(30009, "租户到期时间不合法"),

    // ---- 3xxxx 用户 ----
    USER_NOT_FOUND(31001, "用户不存在"),
    USERNAME_DUPLICATED(31002, "用户名在该租户下已存在"),
    USERNAME_INVALID(31003, "用户名格式不合法"),
    USER_ILLEGAL_STATE(31004, "用户当前状态不允许该操作"),
    PASSWORD_TOO_WEAK(31005, "密码强度不足"),

    // ---- 3xxxx 角色 / 权限 ----
    ROLE_NOT_FOUND(32001, "角色不存在"),
    ROLE_CODE_DUPLICATED(32002, "角色标识已存在"),
    BUILTIN_ROLE_IMMUTABLE(32003, "内置角色不允许修改或删除"),
    PERMISSION_CODE_INVALID(32004, "权限标识格式不合法"),
    ROLE_ILLEGAL_STATE(32005, "角色当前状态不允许该操作"),
    ROLE_IN_USE(32006, "角色已被用户使用，无法删除"),

    // ---- 3xxxx 部门 / 岗位 ----
    DEPT_NOT_FOUND(33001, "部门不存在"),
    DEPT_HAS_CHILDREN(33002, "部门下存在子部门，无法删除"),
    DEPT_HAS_USERS(33003, "部门下存在用户，无法删除"),
    DEPT_CYCLE_DETECTED(33004, "不能将部门移动到它自己的子部门下"),
    POST_NOT_FOUND(33005, "岗位不存在"),
    POST_CODE_DUPLICATED(33006, "岗位编码已存在"),

    // ---- 3xxxx 菜单 ----
    MENU_NOT_FOUND(34001, "菜单不存在"),
    MENU_HAS_CHILDREN(34002, "菜单下存在子节点，无法删除"),
    MENU_INVALID(34003, "菜单配置不合法"),

    // ---- 3xxxx 字典 / 参数 ----
    DICT_TYPE_NOT_FOUND(35001, "字典类型不存在"),
    DICT_TYPE_DUPLICATED(35002, "字典类型编码已存在"),
    CONFIG_NOT_FOUND(35003, "参数不存在"),
    CONFIG_KEY_DUPLICATED(35004, "参数键已存在"),
    CONFIG_BUILTIN_IMMUTABLE(35005, "系统内置参数不允许删除"),
    DICT_DATA_NOT_FOUND(35006, "字典项不存在"),
    DICT_DATA_DUPLICATED(35007, "该字典类型下键值已存在"),
    DICT_TYPE_HAS_DATA(35008, "字典类型下存在字典项，无法删除"),
    ;

    private final int code;
    private final String message;

    @Override
    public int code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
