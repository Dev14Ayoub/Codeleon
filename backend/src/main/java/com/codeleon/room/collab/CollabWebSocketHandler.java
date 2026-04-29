package com.codeleon.room.collab;

import com.codeleon.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollabWebSocketHandler extends BinaryWebSocketHandler {

    private final CollabRoomRegistry registry;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID roomId = (UUID) session.getAttributes().get(CollabHandshakeInterceptor.ATTR_ROOM_ID);
        User user = (User) session.getAttributes().get(CollabHandshakeInterceptor.ATTR_USER);
        Boolean canEdit = (Boolean) session.getAttributes().get(CollabHandshakeInterceptor.ATTR_CAN_EDIT);
        if (roomId == null || user == null) {
            closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            return;
        }
        registry.add(roomId, new CollabSession(session, user.getId(), user.getEmail(), Boolean.TRUE.equals(canEdit)));
        log.debug("Collab connect room={} user={}", roomId, user.getEmail());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        UUID roomId = (UUID) session.getAttributes().get(CollabHandshakeInterceptor.ATTR_ROOM_ID);
        Boolean canEdit = (Boolean) session.getAttributes().get(CollabHandshakeInterceptor.ATTR_CAN_EDIT);
        if (roomId == null) {
            return;
        }
        if (!Boolean.TRUE.equals(canEdit)) {
            // Viewers do not push CRDT or awareness updates upstream.
            return;
        }
        for (CollabSession peer : registry.sessionsOf(roomId)) {
            WebSocketSession peerSession = peer.session();
            if (peerSession.getId().equals(session.getId()) || !peerSession.isOpen()) {
                continue;
            }
            try {
                peerSession.sendMessage(message);
            } catch (IOException ex) {
                log.debug("Failed to relay collab frame to {}", peerSession.getId(), ex);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID roomId = (UUID) session.getAttributes().get(CollabHandshakeInterceptor.ATTR_ROOM_ID);
        if (roomId != null) {
            registry.remove(roomId, session.getId());
        }
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException ignored) {
        }
    }
}
