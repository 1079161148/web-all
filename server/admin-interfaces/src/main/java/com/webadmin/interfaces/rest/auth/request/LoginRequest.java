package com.webadmin.interfaces.rest.auth.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 *
 * <p>注意 Schema 中的字段说明会直接生成到 OpenAPI，成为前端表单的提示文案来源
 * （设计文档 §10.3）。因此这里写的是<b>给用户看的话</b>，不是给开发者看的注释。
 */
@Schema(description = "登录请求")
public record LoginRequest(

        @Schema(description = "租户编码。多租户环境下用于确定在哪个租户内校验账号；"
                + "留空则使用请求上下文中的租户", example = "platform-default")
        String tenantCode,

        @Schema(description = "用户名", example = "admin", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入用户名")
        @Size(max = 64, message = "用户名长度不能超过 64")
        String username,

        @Schema(description = "密码", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请输入密码")
        @Size(max = 128, message = "密码长度非法")
        String password,

        @Schema(description = "图形验证码 id（由 GET /api/v1/auth/captcha 签发）。"
                + "服务端开启验证码时必填；验证码一次性，校验后立即失效")
        @Size(max = 64, message = "验证码标识长度非法")
        String captchaId,

        @Schema(description = "用户输入的验证码（大小写不敏感）")
        @Size(max = 16, message = "验证码长度非法")
        String captchaCode,

        @Schema(description = "免登录天数，仅支持服务端白名单值（默认 1 / 7 / 30）。"
                + "留空或 0 表示不记住：刷新令牌 Cookie 为会话级，关闭浏览器即失效。"
                + "非白名单值会被拒绝，不会静默纠正", example = "7")
        Integer rememberDays
) {
}
