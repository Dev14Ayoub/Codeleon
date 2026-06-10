package com.codeleon.runner.preview;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Request to start a live preview: the dev-server command and the room's files
 * to materialize into the container's workspace.
 */
public record PreviewStartRequest(
        @NotNull @Size(max = 2_000) String command,
        List<@Valid FileEntry> files
) {
    public record FileEntry(
            @NotNull @Size(max = 255) String path,
            @NotNull @Size(max = 100_000) String text
    ) {
    }
}
