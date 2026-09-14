package com.webadmin.interfaces.rest.tenant.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建租户请求。
 *
 * <p>校验策略（设计文档 §10.3）：
 * <ul>
 *   <li>Jakarta Validation 注解的约束会体现在 OpenAPI 中，
 *       前端 ProForm 由生成的 Zod schema 推导出<b>同源</b>的校验规则</li>
 *   <li>但<b>后端才是最终防线</b> —— 前端校验只影响体验</li>
 *   <li>这里只做「形式校验」（长度、格式）；业务不变量（如编码唯一）由领域层与仓储保证</li>
 * </ul>
 */
@Schema(description = "创建租户请求")
public record CreateTenantRequest(

        @Schema(description = "租户编码：6~32 位小写字母、数字、连字符，首尾须为字母或数字",
                example = "acme-corp", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "租户编码不能为空")
        @Pattern(regexp = "^[a-z0-9][a-z0-9-]{4,30}[a-z0-9]$",
                message = "租户编码格式不合法：需 6~32 位小写字母、数字或连字符，且首尾为字母或数字")
        String code,

        @Schema(description = "租户名称", example = "Acme 科技", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "租户名称不能为空")
        @Size(min = 2, max = 64, message = "租户名称长度需在 2~64 之间")
        String name,

        @Schema(description = "套餐编码", example = "PRO", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "套餐编码不能为空")
        String planCode,

        @Schema(description = "备注")
        @Size(max = 500, message = "备注长度不能超过 500")
        String remark
) {
}
