-- =============================================================================
-- Peer-to-peer room chat (human ↔ human, distinct from the AI chat in V7).
--
-- Stores every message exchanged between members of the same room, with
-- optional file attachments held inline as bytea (kept under a 5 MB cap
-- by the upload endpoint). Inline bytea is simpler operationally than a
-- separate object store for a PFE-scale deployment — Postgres dumps stay
-- self-contained, no second persistence layer to manage.
--
-- The user_id FK is ON DELETE SET NULL so a user deletion does not
-- silently erase the room's history. The chat UI renders such orphan
-- rows with the cached user_name and a generic colour.
--
-- One index for the dominant read path: load this room's last N messages
-- in chronological order.
-- =============================================================================

CREATE TABLE room_peer_chat_messages (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    -- Cached at write time so a user delete does not erase the history.
    user_name VARCHAR(180) NOT NULL,
    -- Always non-null even when the user sent only a file — the
    -- frontend stores the file caption (or an empty string) here.
    content TEXT NOT NULL,
    -- File attachment metadata + bytes — all four columns nullable so
    -- a plain text message takes zero attachment storage.
    file_name VARCHAR(255),
    file_type VARCHAR(120),
    file_size INTEGER,
    file_bytes BYTEA,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_room_peer_chat_messages_room_created
    ON room_peer_chat_messages(room_id, created_at);
