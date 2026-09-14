package com.webadmin.infrastructure.fieldpermission;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 字段级权限的自动注入点：在响应体写出之前统一脱敏。
 *
 * <h3>为什么需要一个全局 Advice（而不是让每个接口自己调脱敏器）</h3>
 * 设计文档 §7.4 要求「不侵入业务代码，加注解即可」。
 * 全局 Advice 正是这个承诺的兑现方式：
 * <ul>
 *   <li>开发者只标注解，不需要记得"返回前要脱敏"</li>
 *   <li>新增接口自动受保护 —— 这是最关键的：<b>安全措施如果依赖开发者记得做某件事，
 *       它迟早会被漏掉</b>，而漏掉的那次往往是敏感数据接口</li>
 * </ul>
 *
 * <h3>性能</h3>
 * {@link FieldMasker} 内部对"不含注解的类型"做了缓存与短路，
 * 因此对绝大多数接口（无敏感字段）来说，这里的开销只是一次
 * {@code instanceof} 判断加一次 Map 查表。<b>不为无关接口付出代价</b>，
 * 是全局切面能被长期接受的前提。
 *
 * <h3>与文件下载的关系</h3>
 * 二进制响应（Excel / 文件流）会走 {@code ByteArrayHttpMessageConverter} 等，
 * 其 body 是 {@code byte[]}。本类对非结构化类型直接放行 ——
 * 那类出口的脱敏必须由导出逻辑自己调用 {@link FieldMasker} 完成
 * （因为它们不经过 DTO 装配）。这一点在导出功能开发时必须显式确认，
 * 否则就是「页面打码、导出泄露」的老问题。
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class FieldPermissionResponseAdvice implements ResponseBodyAdvice<Object> {

    private final FieldMasker fieldMasker;

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        // 对所有返回值生效：是否需要处理由 FieldMasker 内部判定。
        // 在这里做类型判断会漏掉泛型擦除后的情况（如 R<PageResult<UserResponse>>）。
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body == null) {
            return null;
        }
        // 二进制与纯文本响应不做结构化脱敏（它们不是 DTO 图）
        if (body instanceof byte[] || body instanceof String || body instanceof CharSequence) {
            return body;
        }
        try {
            return fieldMasker.mask(body);
        } catch (RuntimeException ex) {
            // ⚠️ 脱敏失败绝不能"放行原值" —— 那等于敏感数据泄露。
            // 但也不该让整个接口 500（用户会看到"系统异常"而无从下手）。
            // 折中：记录 error 级日志（便于告警），并抛出让全局异常处理器统一处理，
            // 因为一个"本该脱敏却没脱敏"的响应比一次失败更危险。
            log.error("字段级权限脱敏失败，为避免敏感数据泄露，本次响应将被拒绝。"
                    + " uri={} bodyType={}", request.getURI(),
                    body.getClass().getName(), ex);
            throw new IllegalStateException(
                    "响应脱敏失败，已拒绝返回以防敏感数据泄露", ex);
        }
    }
}
