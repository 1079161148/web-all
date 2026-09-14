package com.webadmin.interfaces.rest.tenant.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 租户续期请求。
 */
@Schema(description = "租户续期请求")
public record RenewTenantRequest(

        @Schema(description = "续期月数", example = "12", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "续期月数不能为空")
        @Min(value = 1, message = "续期月数至少为 1")
        @Max(value = 120, message = "单次续期不能超过 120 个月")
        Integer months,

        @Schema(description = "续期后的套餐编码；为空表示维持当前套餐", example = "ENTERPRISE")
        String planCode
) {
}
