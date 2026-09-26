package com.webadmin.interfaces.rest.auth.response;

import com.webadmin.application.iam.dto.SessionView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * 在线会话（Wire 契约）。
 *
 * <p>与项目其余接口一致：应用层 DTO 与对外契约之间显式映射一层，
 * 让契约的演进成为<b>有意的</b>决策而不是应用层重构的副作用。
 */
@Schema(description = "在线会话")
public record SessionViewResponse(
        @Schema(description = "会话 ID（注销该会话时作为路径参数回传）") String sessionId,
        @Schema(description = "登录 IP（客户端可伪造，仅供展示）") String ip,
        @Schema(description = "客户端标识") String userAgent,
        @Schema(description = "登录时间") Instant loginTime,
        @Schema(description = "最后活跃时间") Instant lastActive,
        @Schema(description = "是否为当前请求所在会话") boolean current) {

    public static SessionViewResponse from(SessionView view) {
        return new SessionViewResponse(
                view.sessionId(),
                view.ip(),
                view.userAgent(),
                view.loginTime(),
                view.lastActive(),
                view.current());
    }
}
