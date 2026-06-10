-- =============================================================================
-- Binary assets for room projects — V11.
--
-- Room files are stored as collaborative text (Yjs Y.Text), which cannot hold
-- binary content. This table stores binary assets (images, fonts, media…) so a
-- real project can carry the files a code editor can't represent as text. The
-- bytes are materialized into the sandbox/preview workspace at run time, and
-- fetched on demand by the editor for thumbnails.
--
-- Portable SQL (separate statements, plain index) so the migration runs on both
-- PostgreSQL (production) and the embedded engine used by the test suite.
-- =============================================================================

CREATE TABLE room_assets (
    id           UUID PRIMARY KEY,
    room_id      UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    path         VARCHAR(255) NOT NULL,
    content_type VARCHAR(150),
    size_bytes   INTEGER NOT NULL,
    bytes        BYTEA NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX ux_room_assets_room_path ON room_assets(room_id, path);
