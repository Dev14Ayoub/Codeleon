package com.codeleon.room.collab;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CollabRoomRegistry {

    private final Map<UUID, Map<String, CollabSession>> rooms = new ConcurrentHashMap<>();

    public void add(UUID roomId, CollabSession session) {
        rooms.computeIfAbsent(roomId, key -> new ConcurrentHashMap<>())
                .put(session.session().getId(), session);
    }

    public void remove(UUID roomId, String sessionId) {
        Map<String, CollabSession> sessions = rooms.get(roomId);
        if (sessions == null) {
            return;
        }
        sessions.remove(sessionId);
        if (sessions.isEmpty()) {
            rooms.remove(roomId);
        }
    }

    public Collection<CollabSession> sessionsOf(UUID roomId) {
        Map<String, CollabSession> sessions = rooms.get(roomId);
        return sessions == null ? java.util.List.of() : sessions.values();
    }
}
