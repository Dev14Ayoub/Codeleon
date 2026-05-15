package com.codeleon.ai.history;

/**
 * The two roles a {@link RoomChatMessage} can carry. Mirrors the OpenAI-
 * style chat schema the rest of the codebase uses ("user" vs "assistant"),
 * stored as the enum name() in {@code room_chat_messages.role}.
 */
public enum RoomChatRole {
    USER,
    ASSISTANT
}
