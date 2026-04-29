package com.codeleon.room.collab;

import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

public record CollabSession(WebSocketSession session, UUID userId, String userEmail, boolean canEdit) {
}
