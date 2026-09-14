package com.webadmin.application.iam.dto;

import java.util.Set;

/**
 * 登录结果。
 *
 * @param accessToken      访问令牌
 * @param expiresInSeconds 有效期（秒）
 * @param user             登录用户的基本信息
 * @param permissions      权限码集合。登录时一次性返回，前端用于按钮级显隐的初始渲染，
 *                         避免"页面先渲染出按钮、再被权限指令移除"的闪烁。
 *                         <b>它只是体验优化，不是安全边界</b> —— 服务端每次请求都会独立判定
 * @param roles            角色标识集合
 */
public record LoginResult(
        String accessToken,
        long expiresInSeconds,
        CurrentUserDTO user,
        Set<String> permissions,
        Set<String> roles
) {
}
