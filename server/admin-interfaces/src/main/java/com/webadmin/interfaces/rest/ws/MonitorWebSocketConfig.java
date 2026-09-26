package com.webadmin.interfaces.rest.ws;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 注册：/ws/monitor，握手复用 SSE 一次性票据鉴权。
 *
 * setAllowedOriginRules("*")：开发期经 Vite 代理（同源）无影响；
 * 生产经 Nginx 同源反代同样无影响 —— 该配置只是不依赖 Origin 头做校验，
 * 真正的准入控制在票据（一次性 + 绑定身份）。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class MonitorWebSocketConfig implements WebSocketConfigurer {

    private final MonitorWebSocketHandler monitorHandler;
    private final TicketHandshakeInterceptor ticketInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(monitorHandler, "/ws/monitor")
                .addInterceptors(ticketInterceptor)
                .setAllowedOrigins("*");
    }
}