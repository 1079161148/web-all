package com.webadmin.common.error;

import lombok.RequiredArgsConstructor;

/**
 * 通用 / 系统级错误码（1xxxx）。
 *
 * <p>认证授权码段 2xxxx 亦在此（它们属于框架级而非业务领域），
 * 领域码段见各限界上下文的枚举（3xxxx 起）。
 *
 * <p><b>为什么手写 {@code code()} / {@code message()} 而不用 Lombok {@code @Getter}：</b>
 * {@link ErrorCode} 采用 record 风格的访问器命名，而 Lombok 生成的是
 * {@code getCode()} / {@code getMessage()}，二者签名不匹配、无法满足接口。
 * 更重要的是 {@code httpStatus()} —— 它是接口的 <b>default 方法</b>，
 * 若靠 Lombok 生成的 {@code getHttpStatus()} 就会静默地永远返回默认值 200，
 * 导致 401/403 失效。这类问题不会编译报错，只会让认证语义出错。
 */
@RequiredArgsConstructor
public enum CommonErrorCode implements ErrorCode {

    SUCCESS(0, "成功"),

    // ---- 1xxxx 通用 / 系统 ----
    PARAM_INVALID(10000, "请求参数不合法"),
    UNKNOWN_ERROR(10001, "系统繁忙，请稍后重试"),
    RECORD_NOT_FOUND(10002, "记录不存在或已被删除"),
    CONCURRENT_MODIFICATION(10003, "数据已被他人修改，请刷新后重试"),
    REPEAT_SUBMIT(10004, "请勿重复提交"),
    RATE_LIMITED(10005, "操作过于频繁，请稍后重试"),
    OPERATION_NOT_ALLOWED(10006, "当前状态下不允许该操作"),
    FILE_TYPE_NOT_ALLOWED(10007, "文件类型不被允许"),

    // ---- 2xxxx 认证与授权 ----
    UNAUTHENTICATED(20001, "登录状态已失效，请重新登录", 401),
    ACCESS_DENIED(20002, "没有该操作的权限", 403),
    BAD_CREDENTIALS(20003, "账号或密码错误"),
    ACCOUNT_LOCKED(20004, "账号已被锁定，请联系管理员"),
    ACCOUNT_DISABLED(20005, "账号已被停用"),
    TOKEN_EXPIRED(20006, "登录已过期，请重新登录", 401),
    CAPTCHA_INVALID(20007, "验证码错误或已失效"),
    ;

    private final int code;
    private final String message;
    private final int httpStatus;

    CommonErrorCode(int code, String message) {
        this(code, message, 200);
    }

    @Override
    public int code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
