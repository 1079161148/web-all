package com.webadmin.interfaces.rest.tenant.response;

import com.webadmin.domain.iam.model.tenant.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 租户响应（对外契约）。
 *
 * <p>这是 OpenAPI 的直接来源，前端 {@code packages/api/src/generated/types.gen.ts}
 * 中的 {@code TenantResponse} 由本类生成，<b>前端禁止手写同名类型</b>。
 *
 * <p>字段权限脱敏（设计文档 §7.4）应作用在本层：无权限的调用者拿不到的字段
 * 直接不出现在 JSON 中，而不是返回后由前端隐藏。
 */
@Schema(description = "租户信息（对外契约）")
public record TenantResponse(

        @Schema(description = "租户 ID", example = "1000000000000000001")
        Long id,

        @Schema(description = "租户编码，全局唯一且创建后不可修改", example = "acme-corp")
        String code,

        @Schema(description = "租户名称", example = "Acme 科技")
        String name,

        @Schema(description = """
                租户状态。语义：
                - PENDING：已创建待激活
                - ACTIVE：正常可用
                - SUSPENDED：已暂停（数据保留，拒绝访问）
                - EXPIRED：已过期（可续费恢复）
                - CLOSED：已关闭（终态，不可恢复）""")
        TenantStatus status,

        @Schema(description = "套餐编码", example = "PRO")
        String planCode,

        @Schema(description = "套餐名称", example = "专业版")
        String planName,

        @Schema(description = "剩余用户数配额")
        Long remainingUsers,

        @Schema(description = "剩余存储配额（字节）")
        Long remainingStorageBytes,

        @Schema(description = "剩余月度 API 调用配额")
        Long remainingApiCalls,

        @Schema(description = "到期时间（UTC）")
        Instant expireTime,

        @Schema(description = "备注")
        String remark,

        @Schema(description = "创建时间（UTC）")
        Instant createTime
) {
}
