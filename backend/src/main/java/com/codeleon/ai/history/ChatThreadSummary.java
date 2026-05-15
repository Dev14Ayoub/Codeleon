package com.codeleon.ai.history;

import java.util.UUID;

/**
 * One row in the room owner's "review someone's chat" picker: a member
 * who has written at least one message in this room, with their total
 * message count for context. Returned by GET /rooms/{id}/chat/threads,
 * which is owner-only.
 */
public record ChatThreadSummary(
        UUID userId,
        String userName,
        long messageCount
) {
}
