package com.codeleon.ai.history;

import java.time.Instant;
import java.util.UUID;

/**
 * One persisted message as returned by GET /rooms/{id}/chat/history.
 * userId / userName are nullable because the originating user may have
 * been deleted (ON DELETE SET NULL on the FK); the UI renders such rows
 * as "Someone".
 */
public record ChatHistoryMessage(
        UUID id,
        UUID userId,
        String userName,
        RoomChatRole role,
        String content,
        Instant createdAt
) {
    public static ChatHistoryMessage of(RoomChatMessage msg) {
        var user = msg.getUser();
        return new ChatHistoryMessage(
                msg.getId(),
                user != null ? user.getId() : null,
                user != null ? user.getFullName() : null,
                msg.getRole(),
                msg.getContent(),
                msg.getCreatedAt()
        );
    }
}
