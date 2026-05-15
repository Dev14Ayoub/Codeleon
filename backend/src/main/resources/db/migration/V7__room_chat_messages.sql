-- =============================================================================
-- AI chat history persistence.
--
-- Each row is one turn in a per-room, per-user conversation: either the
-- user's own message ('USER') or the assistant's reply to it ('ASSISTANT').
-- The user_id is the asker for both roles — a single user_id groups the
-- whole thread so that owner-side review can fetch one member's chat
-- with a simple WHERE user_id = ? clause.
--
-- The user_id FK is ON DELETE SET NULL so that deleting a user does not
-- silently erase historical conversations; the chat panel renders such
-- orphan rows as "Someone" and life goes on.
--
-- Two indexes for the two read paths:
--   * (room_id, user_id, created_at) — invited members loading their
--     own thread in a room, the dominant call.
--   * (room_id, created_at) — owner review reading every message in
--     the room ordered chronologically across users.
-- =============================================================================

CREATE TABLE room_chat_messages (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_room_chat_messages_room_user_created
    ON room_chat_messages(room_id, user_id, created_at);

CREATE INDEX idx_room_chat_messages_room_created
    ON room_chat_messages(room_id, created_at);
