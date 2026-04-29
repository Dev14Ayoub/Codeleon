CREATE TABLE room_files (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    path VARCHAR(255) NOT NULL,
    language VARCHAR(40) NOT NULL,
    state_update BYTEA,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_room_file_path UNIQUE (room_id, path)
);

CREATE INDEX idx_room_files_room_id ON room_files(room_id);
