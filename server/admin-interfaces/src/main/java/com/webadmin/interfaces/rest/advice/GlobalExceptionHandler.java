package com.webadmin.interfaces.rest.advice;

import com.webadmin.common.api.R;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import com.webadmin.common.error.ErrorCode;
import com.webadmin.domain.shared.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 全局异常处理。
 *
 * <p>设计约束（设计文档 §4.10 / §十四）：
 * <ul>
 *   <li>统一转换为 {@link R}，Controller 内<b>禁止再写 try-catch 返回错误码</b></li>
 *   <li>业务错误返回 <b>HTTP 200</b> + 业务码，让前端拦截器读 {@code code} 分流；
 *       仅认证/授权类错误返回真实 401/403</li>
 *   <li><b>异常堆栈绝不返回前端</b>；系统异常只回传可读提示 + traceId</li>
 *   <li>提示信息必须<b>可操作</b>（说明原因或下一步），不要只说"操作失败"</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：可预期，记 WARN 不记 ERROR，不打印堆栈。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBizException(BizException ex, HttpServletRequest request) {
        log.warn("业务异常 [{}] {} -> code={}, msg={}",
                request.getMethod(), request.getRequestURI(), ex.getCode(), ex.getMessage());
        return ResponseEntity
                .status(ex.getHttpStatus())
                .body(R.fail(ex.getErrorCode(), ex.getMessage()));
    }

    /**
     * 领域异常：聚合根 / 值对象违反不变量时抛出。
     *
     * <p>与业务异常同等对待 —— 都属于「可预期的规则拒绝」，不是系统故障。
     */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<R<Void>> handleDomainException(DomainException ex, HttpServletRequest request) {
        ErrorCode errorCode = ex.getErrorCode();
        log.warn("领域规则拒绝 [{}] {} -> code={}, msg={}",
                request.getMethod(), request.getRequestURI(), errorCode.code(), ex.getMessage());
        return ResponseEntity
                .status(errorCode.httpStatus())
                .body(R.fail(errorCode, ex.getMessage()));
    }

    /** {@code @RequestBody} 上的 {@code @Valid} 校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .distinct()
                .collect(Collectors.joining("；"));
        log.warn("请求体校验失败 [{}] {} -> {}",
                request.getMethod(), request.getRequestURI(), detail);
        return badRequest(detail);
    }

    /** 表单 / 查询参数绑定校验失败。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBindException(BindException ex, HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .distinct()
                .collect(Collectors.joining("；"));
        log.warn("参数绑定失败 [{}] {} -> {}",
                request.getMethod(), request.getRequestURI(), detail);
        return badRequest(detail);
    }

    /** {@code @RequestParam} / {@code @PathVariable} 上的约束校验失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String detail = ex.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .distinct()
                .collect(Collectors.joining("；"));
        log.warn("参数约束校验失败 [{}] {} -> {}",
                request.getMethod(), request.getRequestURI(), detail);
        return badRequest(detail);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        String detail = "缺少必需参数：" + ex.getParameterName();
        log.warn("缺少请求参数 [{}] {} -> {}",
                request.getMethod(), request.getRequestURI(), detail);
        return badRequest(detail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<R<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String detail = "参数 [" + ex.getName() + "] 类型不正确";
        log.warn("参数类型不匹配 [{}] {} -> {}",
                request.getMethod(), request.getRequestURI(), detail);
        return badRequest(detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleNotReadable(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("请求体无法解析 [{}] {} -> {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return badRequest("请求体格式不正确，请检查 JSON 结构");
    }

    /**
     * 兜底：未预期的系统异常。
     *
     * <p>必须记录完整堆栈用于排查，但<b>只向调用方回传可读提示 + traceId</b>，
     * 绝不暴露堆栈、SQL 或类名。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        String traceId = currentTraceId();
        log.error("系统异常 [{}] {} traceId={}",
                request.getMethod(), request.getRequestURI(), traceId, ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(CommonErrorCode.UNKNOWN_ERROR,
                        "系统繁忙，请稍后重试（traceId: " + traceId + "）"));
    }

    private ResponseEntity<R<Void>> badRequest(String message) {
        return ResponseEntity
                .ok(R.fail(CommonErrorCode.PARAM_INVALID, message));
    }

    /**
     * 读取当前 traceId。
     *
     * <p>P1 接入 Micrometer Tracing 后，从 {@code Tracer} 或 MDC 读取真实链路 ID；
     * 当前回退为占位符，保证前端「错误提示可复制 traceId」的交互契约不被破坏。
     */
    private String currentTraceId() {
        String traceId = org.slf4j.MDC.get("traceId");
        return traceId == null ? "n/a" : traceId;
    }
}
