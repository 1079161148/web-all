package com.webadmin.application.iam.dto;

import java.util.Set;

/**
 * 登录结果。
 *
 * @param accessToken             访问令牌（短效，默认 30 分钟）
 * @param expiresInSeconds        访问令牌有效期（秒）
 * @param refreshToken            刷新令牌（长效不透明随机串，默认 7 天）。
 *                                <b>HTTP 响应中不出现</b> —— 它经 HttpOnly Cookie 下发
 *                                （见 AuthController），本字段仅在应用层内部流转
 *                                （控制器取它写 Cookie，随后置 null 剥离出响应）。
 *                                之后轮换出的新令牌同样只走 Cookie —— 旧令牌随即作废（轮换语义）
 * @param refreshExpiresInSeconds 刷新令牌有效期（秒）
 * @param user                    登录用户的基本信息
 * @param permissions             权限码集合。登录时一次性返回，前端用于按钮级显隐的初始渲染，
 *                                避免"页面先渲染出按钮、再被权限指令移除"的闪烁。
 *                                <b>它只是体验优化，不是安全边界</b> —— 服务端每次请求都会独立判定
 * @param roles                   角色标识集合
 * @param rememberDays            本次会话的免登录天数（0 = 不记住）。
 *                                控制器据此决定刷新令牌 Cookie 是否带 Max-Age：
 *                                0 → 会话级 Cookie（关浏览器即失效）；
 *                                N → Max-Age = refreshExpiresInSeconds。
 *                                刷新时该值由服务端会话标记回读（见 LoginRememberPort），
 *                                <b>不取自本次请求</b> —— 否则用户可以在刷新时
 *                                把"不记住"的会话悄悄升级成"记住 30 天"
 */
public record LoginResult(
        String accessToken,
        long expiresInSeconds,
        String refreshToken,
        long refreshExpiresInSeconds,
        CurrentUserDTO user,
        Set<String> permissions,
        Set<String> roles,
        int rememberDays
) {
}
