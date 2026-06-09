-- =============================================================================
-- Voice messages on the peer chat — V10.
--
-- Reuses the existing file_* columns on room_peer_chat_messages: a voice
-- message is just an attachment with a file_type starting with "audio/".
-- Two new columns make the audio UX possible:
--
--   audio_duration_ms : cached from the client at upload so the UI can
--                       display the duration without decoding the audio.
--   expires_at        : timestamp after which the row is purged by the
--                       PeerChatExpirationJob. NULL for normal messages
--                       (never expire); set to now() + ttl for audio
--                       messages, giving them a 24h shelf life.
--
-- The partial index on expires_at keeps the cleanup query O(expired-rows)
-- and the index itself tiny because text-only messages never get an
-- expires_at value.
-- =============================================================================

-- Separate ALTER statements (not a single comma-joined ADD COLUMN) so the
-- migration is portable: PostgreSQL accepts both forms, but H2 — used by the
-- test suite — only accepts one ADD COLUMN per ALTER TABLE.
ALTER TABLE room_peer_chat_messages ADD COLUMN audio_duration_ms INTEGER;
ALTER TABLE room_peer_chat_messages ADD COLUMN expires_at TIMESTAMP;

-- Plain (non-partial) index on expires_at. A partial index with a WHERE
-- clause is PostgreSQL-only and breaks H2; the full index serves the purge
-- query just as well — text-only rows simply index a NULL value.
CREATE INDEX idx_room_peer_chat_messages_expires_at
    ON room_peer_chat_messages(expires_at);
