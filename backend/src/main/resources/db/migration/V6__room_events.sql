-- =============================================================================
-- Activity feed support: append-only event log per room + a denormalised
-- "last edited by" pointer on the rooms table so the dashboard listing
-- can render the field in O(1) per row instead of joining the events
-- table for every card it draws.
--
-- Two design choices worth keeping in mind:
--   * Events are append-only. We never UPDATE or DELETE rows in
--     room_events; that is what keeps the audit story honest and lets
--     the feed do a cheap range scan by created_at.
--   * payload is a TEXT JSON blob, not a real JSONB column. Postgres
--     JSONB would be nicer but H2 used by the test profile does not
--     support it well, and the queries we run never look inside the
--     payload, so a plain TEXT keeps both databases happy.
-- =============================================================================

CREATE TABLE room_events (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    type VARCHAR(40) NOT NULL,
    payload TEXT,
    created_at TIMESTAMP NOT NULL
);

-- Cross-room timeline for the dashboard sidebar: most recent first.
CREATE INDEX idx_room_events_created_at ON room_events(created_at DESC);

-- Per-room timeline for an in-room "What happened?" panel (future).
CREATE INDEX idx_room_events_room_created ON room_events(room_id, created_at DESC);

ALTER TABLE rooms ADD COLUMN last_edited_by_id UUID REFERENCES users(id) ON DELETE SET NULL;
