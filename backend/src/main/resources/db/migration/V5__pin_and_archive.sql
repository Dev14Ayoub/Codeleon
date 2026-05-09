-- =============================================================================
-- Pin & Archive support for the user dashboard.
--
-- Two related but separate concepts:
--   * pinning is per-user: each (user_id, room_id) pair can be marked so
--     the room bubbles to the top of that user's dashboard. Joining or
--     leaving a room does not affect anyone else's pins.
--   * archiving is per-room: it is a soft-delete style flag set by the
--     room owner. Archived rooms are hidden from the default dashboard
--     listing but still kept in the database.
--
-- Composite primary key on room_pins gives us idempotent pin/unpin without
-- needing a separate uniqueness constraint.
-- =============================================================================

CREATE TABLE room_pins (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    pinned_at TIMESTAMP NOT NULL,
    PRIMARY KEY (user_id, room_id)
);

CREATE INDEX idx_room_pins_user ON room_pins(user_id);

ALTER TABLE rooms ADD COLUMN archived_at TIMESTAMP;
CREATE INDEX idx_rooms_archived_at ON rooms(archived_at);
