package com.gitnova.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket Handler — 向仓库成员推送 Code Review 结果
 *
 * Phase 4/6: Agent review 完成后通过 WebSocket 实时推送通知
 */
@Component
public class ReviewPushHandler extends TextWebSocketHandler {

    // repoId → session 集合（一个仓库可能有多个成员在线）
    private final ConcurrentHashMap<Long, java.util.Set<WebSocketSession>> repoSessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // TODO: Phase 6 — 从 session 参数中解析 repoId，注册到 repoSessions
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // TODO: Phase 6 — 处理客户端消息（订阅/取消订阅仓库）
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // TODO: Phase 6 — 从 repoSessions 中移除 session
    }

    /**
     * 向指定仓库的所有在线成员推送消息
     */
    public void pushToRepo(Long repoId, String message) throws IOException {
        java.util.Set<WebSocketSession> sessions = repoSessions.get(repoId);
        if (sessions != null) {
            TextMessage textMessage = new TextMessage(message);
            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            }
        }
    }
}
