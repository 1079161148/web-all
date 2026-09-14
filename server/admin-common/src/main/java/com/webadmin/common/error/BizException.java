package com.webadmin.common.error;

import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>设计约束（见设计文档 §4.10）：
 * <ul>
 *   <li>所有可预期的业务失败必须抛本异常或其子类，<b>禁止抛裸 {@code RuntimeException}</b></li>
 *   <li>由全局异常处理器统一转换为 {@code R}，Controller 内<b>禁止 try-catch 后自行返回错误码</b></li>
 *   <li>必须可触发事务回滚 —— 因此继承 {@link RuntimeException}</li>
 * </ul>
 */
@Getter
public class BizException extends RuntimeException {

    private final transient ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    /**
     * 覆盖默认提示。仅用于需要补充上下文的场景（如"租户编码 tenant-a 已存在"），
     * 且补充内容不得包含内部实现细节。
     */
    public BizException(ErrorCode errorCode, String overrideMessage) {
        super(overrideMessage);
        this.errorCode = errorCode;
    }

    public int getCode() {
        return errorCode.code();
    }

    public int getHttpStatus() {
        return errorCode.httpStatus();
    }

    /** 业务异常不需要堆栈，避免高频路径上的填充开销。 */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
