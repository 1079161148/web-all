package com.webadmin.interfaces.rest.ws;

import com.webadmin.application.iam.port.MessageStreamPort;
import com.webadmin.common.error.BizException;
import com.webadmin.common.error.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket 握手拦截器：复用 SSE 的一次性票据鉴权。
 *
 * 浏览器的 WebSocket API 同样不能带自定义请求头 —— 与 SSE 面临
 * 一模一样的问题，而票据换连接（短时、一次性、绑定身份）已在消息中心
 * 落地并被测试覆盖。同构问题复用同构解：握手时从查询串取 ticket，
 * 消费失败立即拒绝握手。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TicketHandshakeInterceptor implements HandshakeInterceptor {

    private final MessageStreamPort messageStreamPort;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return false;
        }
        String ticket = servletRequest.getServletRequest().getParameter("ticket");
        MessageStreamPort.TicketOwner owner = ticket == null ? null : messageStreamPort.consumeTicket(ticket);
        if (owner == null) {
            log.warn("[WS] 握手被拒：票据缺失或无效 ip={}",
                    servletRequest.getServletRequest().getRemoteAddr());
            throw new BizException(CommonErrorCode.UNAUTHENTICATED, "连接票据无效或已过期");
        }
        attributes.put("tenantId", owner.tenantId());
        attributes.put("userId", owner.userId());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }
}