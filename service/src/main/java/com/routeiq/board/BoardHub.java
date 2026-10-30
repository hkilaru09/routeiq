package com.routeiq.board;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

/** WebSocket endpoint for the live board. Clients connect to {@code /ws/board?tenant=<id>}. */
@Component
public class BoardHub extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BoardHub.class);

    private record Subscriber(WebSocketSession session, String tenantId) {
    }

    private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String tenant = session.getUri() == null ? null
                : UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst("tenant");
        // Slow clients are dropped rather than allowed to block the broadcaster.
        var safe = new ConcurrentWebSocketSessionDecorator(session, 5_000, 512 * 1024);
        subscribers.put(session.getId(), new Subscriber(safe, tenant));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscribers.remove(session.getId());
    }

    public void broadcast(String tenantId, String json) {
        TextMessage message = new TextMessage(json);
        subscribers.values().forEach(sub -> {
            if (sub.tenantId() != null && !sub.tenantId().equals(tenantId)) {
                return;
            }
            try {
                sub.session().sendMessage(message);
            } catch (IOException | IllegalStateException e) {
                log.debug("dropping board subscriber {}: {}", sub.session().getId(), e.toString());
                subscribers.remove(sub.session().getId());
            }
        });
    }

    public int subscriberCount() {
        return subscribers.size();
    }
}
