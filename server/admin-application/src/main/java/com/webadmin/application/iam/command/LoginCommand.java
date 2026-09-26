package com.webadmin.application.iam.command;

/**
 * 登录命令。
 *
 * @param tenantCode 租户编码。多租户 SaaS 的登录表单通常要求先选/填租户，
 *                   因为它决定"在哪个租户里查这个用户名"。
 *                   允许为空：此时使用请求上下文里已有的租户（如请求头或网关注入）
 * @param username   用户名
 * @param password   明文密码。<b>只在本次调用内存活</b>，不做任何持久化或日志输出
 * @param loginIp    客户端 IP，用于登录审计与失败锁定策略（按 IP 限流）
 * @param captchaId      图形验证码 id（服务端签发时下发）
 * @param captchaCode    用户输入的验证码；校验即消费，不可重放
 * @param rememberDays   免登录天数（1/7/30，服务端白名单校验）。
 *                       0 或 null = 不记住（刷新令牌 Cookie 为会话级，关浏览器即失效）。
 *                       <b>不信任客户端传值</b>：非法值直接拒绝，而不是"取个接近的"，
 *                       否则"传 3650"就能悄悄换取十年会话
 */
public record LoginCommand(
        String tenantCode,
        String username,
        String password,
        String loginIp,
        String captchaId,
        String captchaCode,
        Integer rememberDays
) {
}
