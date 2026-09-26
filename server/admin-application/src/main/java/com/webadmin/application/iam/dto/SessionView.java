package com.webadmin.application.iam.dto;

import java.time.Instant;

/**
 * 在线会话视图。
 *
 * @param sessionId 会话 ID（注销该会话时回传给 {@code DELETE /auth/sessions/{id}}）
 * @param ip        登录 IP。⚠️ 来自客户端声明，<b>仅用于展示</b>，不能作为"这是谁的设备"的依据
 * @param userAgent 客户端标识（已清洗：去分隔符、截断），可能为空
 * @param current   是否为<b>当前这次请求</b>所在会话。
 *                  前端据此把"当前设备"与其它设备区分开 ——
 *                  注销当前会话等价于退出登录，交互上应当给出不同的确认文案
 */
public record SessionView(
        String sessionId,
        String ip,
        String userAgent,
        Instant loginTime,
        Instant lastActive,
        boolean current) {
}
