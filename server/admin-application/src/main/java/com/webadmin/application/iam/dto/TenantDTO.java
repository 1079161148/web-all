package com.webadmin.application.iam.dto;

import com.webadmin.domain.iam.model.tenant.TenantStatus;
import java.time.Instant;

/**
 * 租户读模型（应用层 DTO）。
 *
 * <p>刻意<b>不携带任何 OpenAPI / Jackson 注解</b>：
 * <ul>
 *   <li>应用层不应依赖接口文档库（{@code io.swagger.*}）与 Web 序列化库</li>
 *   <li>对外契约由 interface 层的 {@code TenantResponse} 承担，
 *       {@code @Schema} 注解写在那里</li>
 *   <li>二者转换由 {@code TenantAssembler} 完成 —— 看似多一层，
 *       但换来了「字段权限脱敏、枚举转标签」等<b>线上契约与内部读模型解耦</b>的能力</li>
 * </ul>
 */
public record TenantDTO(
        Long id,
        String code,
        String name,
        TenantStatus status,
        String planCode,
        String planName,
        Long remainingUsers,
        Long remainingStorageBytes,
        Long remainingApiCalls,
        Instant expireTime,
        String remark,
        Instant createTime
) {
}
