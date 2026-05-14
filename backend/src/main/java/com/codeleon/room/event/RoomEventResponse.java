package com.codeleon.room.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One line in the dashboard activity feed. Carries enough room and user
 * context for the frontend to render "Alice ran code in Algorithms Lab"
 * without a second lookup. {@code userId} / {@code userName} are nullable
 * because the originating user may have been deleted (the FK is ON DELETE
 * SET NULL); the frontend renders those as "Someone".
 */
public record RoomEventResponse(
        UUID id,
        UUID roomId,
        String roomName,
        UUID userId,
        String userName,
        String type,
        Map<String, String> payload,
        Instant createdAt
) {
    public static RoomEventResponse of(RoomEvent event, ObjectMapper objectMapper) {
        var user = event.getUser();
        var room = event.getRoom();
        return new RoomEventResponse(
                event.getId(),
                room.getId(),
                room.getName(),
                user != null ? user.getId() : null,
                user != null ? user.getFullName() : null,
                event.getType(),
                parsePayload(event.getPayload(), objectMapper),
                event.getCreatedAt()
        );
    }

    private static Map<String, String> parsePayload(String payload, ObjectMapper objectMapper) {
        if (payload == null || payload.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payload, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            // A malformed payload should never sink the whole feed request —
            // return an empty map and let the frontend fall back to the
            // generic phrasing for that event type.
            return Map.of();
        }
    }
}
