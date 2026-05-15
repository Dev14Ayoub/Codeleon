package com.codeleon.ai;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Batch index request: every file in the room in one call.
 *
 * The frontend reads each file's Y.Text client-side (even closed tabs)
 * and ships the whole project here so the RAG index covers all files,
 * not just whichever tab happened to be open. Re-indexing is safe and
 * idempotent — RoomFileIndexer deletes a path's existing chunks before
 * inserting fresh ones, so calling this repeatedly never duplicates.
 */
public record IndexAllRequest(
        @NotEmpty @Size(max = 200) List<@NotNull IndexFile> files
) {
    public record IndexFile(
            String path,
            @NotNull @Size(max = 200_000) String text
    ) {
        public String pathOrDefault() {
            return (path == null || path.isBlank()) ? "main" : path;
        }
    }
}
