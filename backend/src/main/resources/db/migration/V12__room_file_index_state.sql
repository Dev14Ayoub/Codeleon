-- =============================================================================
-- Persistent record of what the AI/RAG pipeline has already indexed.
--
-- Until now the "what is indexed" baseline lived only in the chat panel's
-- React state: it was lost on every refresh, was per-tab, and was not shared
-- between collaborators. That forced a full project re-embed far more often
-- than necessary and meant a freshly opened room was only indexed once the
-- user opened the chat and sent a message.
--
-- This table stores one row per indexed file: the SHA-256 of the exact text
-- that was embedded. A client can fetch the whole map cheaply on mount,
-- diff it against the project's current content, and re-embed only the files
-- that actually changed — across refreshes, tabs and collaborators.
--
-- ON DELETE CASCADE so deleting a room (owner or admin path) drops its index
-- state automatically, in addition to the best-effort vector purge in
-- RoomFileIndexer#deleteRoomIndexQuietly.
-- =============================================================================

CREATE TABLE room_file_index_state (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    path VARCHAR(1024) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    indexed_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_room_file_index_state_room_path UNIQUE (room_id, path)
);

CREATE INDEX idx_room_file_index_state_room ON room_file_index_state(room_id);
