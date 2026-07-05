package com.gitnova.config;

import com.gitnova.websocket.ReviewPushHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置 — Phase 4 Agent review 结果实时推送
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ReviewPushHandler reviewPushHandler;

    public WebSocketConfig(ReviewPushHandler reviewPushHandler) {
        this.reviewPushHandler = reviewPushHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(reviewPushHandler, "/ws/review")
                .setAllowedOrigins("*");
    }
}
