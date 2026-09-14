package com.webadmin.common.api;

import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.common.error.ErrorCode;

/**
 * 统一响应体。
 *
 * <p>约定（设计文档 §10.3）：
 * <ul>
 *   <li>{@code code == 0} 表示成功，非 0 为业务错误码</li>
 *   <li>HTTP 状态码对业务错误返回 {@code 200}，由前端读取 {@code code} 分流；
 *       仅认证/授权失败返回 {@code 401/403}</li>
 *   <li>前端类型由 OpenAPI 生成，<b>禁止手写</b></li>
 * </ul>
 *
 * @param code 业务码，0 为成功
 * @param msg  提示信息
 * @param data 数据体，成功时可能为 {@code null}
 */
public record R<T>(int code, String msg, T data) {

    public static final int SUCCESS_CODE = 0;

    public static <T> R<T> ok() {
        return new R<>(SUCCESS_CODE, CommonErrorCode.SUCCESS.message(), null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(SUCCESS_CODE, CommonErrorCode.SUCCESS.message(), data);
    }

    public static <T> R<T> ok(T data, String msg) {
        return new R<>(SUCCESS_CODE, msg, data);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return new R<>(errorCode.code(), errorCode.message(), null);
    }

    public static <T> R<T> fail(ErrorCode errorCode, String overrideMessage) {
        return new R<>(errorCode.code(), overrideMessage, null);
    }

    public boolean isSuccess() {
        return code == SUCCESS_CODE;
    }
}
