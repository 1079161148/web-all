package com.webadmin.common.error;

/**
 * 错误码契约。
 *
 * <p>实现方为各层枚举（{@code CommonErrorCode} / {@code IamErrorCode} / ...）。
 * 领域层也可实现本接口，从而在不依赖框架的前提下表达错误语义。
 *
 * <p>码段约定：
 * <ul>
 *   <li>{@code 0} —— 成功</li>
 *   <li>{@code 1xxxx} —— 通用 / 系统</li>
 *   <li>{@code 2xxxx} —— 认证与授权</li>
 *   <li>{@code 3xxxx} —— IAM 领域</li>
 * </ul>
 */
public interface ErrorCode {

    /** 业务错误码。 */
    int code();

    /** 面向用户的错误提示，必须可操作（说明原因或下一步），不得暴露内部实现。 */
    String message();

    /** 默认 HTTP 状态码。业务错误通常返回 200，由前端读取 code 分流。 */
    default int httpStatus() {
        return 200;
    }
}
