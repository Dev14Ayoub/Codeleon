-- =============================================================================
-- Multi-file rooms.
-- =============================================================================
-- Until V2, every room had exactly one row in `room_files` (path = 'main') and
-- the Y.Doc snapshot lived on that row. The Y.Doc held a single Y.Text keyed
-- "main".
--
-- Multi-file rooms keep ONE Y.Doc per room but with N Y.Texts inside, one per
-- file path. Therefore the snapshot is logically a room-level artifact, not a
-- file-level one. We move `state_update` from `room_files` to `rooms` and let
-- `room_files` become pure metadata (path, language, timestamps).
--
-- Existing data is preserved: the legacy 'main' file's snapshot is copied onto
-- the parent room before the column is dropped. The Y.Text key for the legacy
-- file remains "main" inside the Y.Doc — Phase 2 frontend will continue to bind
-- the Monaco editor to Y.Text("main") for this specific path.
-- =============================================================================

ALTER TABLE rooms ADD COLUMN state_update BYTEA;

UPDATE rooms r
SET state_update = (
    SELECT rf.state_update
    FROM room_files rf
    WHERE rf.room_id = r.id AND rf.path = 'main'
    LIMIT 1
)
WHERE EXISTS (
    SELECT 1
    FROM room_files rf
    WHERE rf.room_id = r.id AND rf.path = 'main' AND rf.state_update IS NOT NULL
);

ALTER TABLE room_files DROP COLUMN state_update;
