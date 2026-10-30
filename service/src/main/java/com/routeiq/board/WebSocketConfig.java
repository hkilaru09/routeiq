package com.routeiq.board;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final BoardHub hub;
    private final String[] allowedOrigins;

    public WebSocketConfig(BoardHub hub, @Value("${routeiq.board.allowed-origins:*}") String[] allowedOrigins) {
        this.hub = hub;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(hub, "/ws/board").setAllowedOriginPatterns(allowedOrigins);
    }
}
