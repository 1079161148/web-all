package com.webadmin.application.iam.dto;

import java.time.Instant;
import java.util.List;

/**
 * 用户读模型（<b>应用层内部类型</b>）。
 *
 * <h3>为什么它不带 {@code @Schema} 注解</h3>
 * 初版把 swagger 注解直接标在这个类上，结果<b>编译不过</b> ——
 * {@code admin-application} 没有（也<b>不该有</b>）springdoc 依赖。
 *
 * <p>这不是"加个依赖就好"的小事：OpenAPI 是<b>对外契约</b>，
 * 属于 interfaces 层的关注点。把契约注解放进应用层，会让
 * "改一个字段说明"都能牵动应用层重新编译，也让应用层被一个
 * 纯粹的 API 文档框架绑架。<b>契约类型应当住在 interfaces 层</b>
 * （与已有的 {@code TenantResponse} 一致）。
 *
 * <p>因此职责拆分为：
 * <pre>
 *   UserDTO      —— 应用层读模型，只承载数据。可被多个调用方复用
 *                    （如将来的导出、内部批处理），不受 API 形态影响
 *   UserResponse —— 接口层契约，带 @Schema 注解，并承载字段级权限声明
 * </pre>
 * 转换由 {@code UserResponse.from(UserDTO)} 完成，页面的批量转换走
 * {@code PageResult.map(...)}。
 *
 * <h3>字段级权限注解为什么也不在这里</h3>
 * 脱敏由 {@code FieldPermissionResponseAdvice} 在<b>响应对象</b>写出前执行，
 * 它读的是最终被序列化的那个对象的注解。既然对外的是 {@code UserResponse}，
 * 注解就必须在那里 —— 标在中间的 {@code UserDTO} 上不会生效，而且
 * 这种"标了但没生效"的写法比不标更难排查。
 */
public record UserDTO(
        Long id,
        Long tenantId,
        Long deptId,
        String deptName,
        String username,
        String nickname,
        String phone,
        String email,
        Integer sex,
        String avatar,
        String status,
        String loginIp,
        Instant loginTime,
        List<Long> roleIds,
        String roleNames,
        Instant createTime
) {
}
