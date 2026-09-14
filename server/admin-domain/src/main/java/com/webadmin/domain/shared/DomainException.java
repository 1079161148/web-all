package com.webadmin.domain.shared;

import com.webadmin.common.error.ErrorCode;

/**
 * 领域异常基类。
 *
 * <p>刻意<b>不继承</b> {@code BizException}：领域层不应感知「业务接口」语义，
 * 只表达「领域规则被违反」。二者统一由全局异常处理器转换为 {@code R}。
 *
 * <p>必须继承 {@link RuntimeException}，否则无法触发事务回滚。
 */
public abstract class DomainException extends RuntimeException {

    private final transient ErrorCode errorCode;

    protected DomainException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    protected DomainException(ErrorCode errorCode, String detail) {
        super(errorCode.message() + "：" + detail);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public int getCode() {
        return errorCode.code();
    }

    /** 领域异常在业务流中属于可预期结果，无需堆栈。 */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
