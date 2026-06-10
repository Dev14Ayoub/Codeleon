package com.codeleon.runner.preview;

/**
 * Current preview state for a room. {@code url} is the path the SPA points its
 * preview iframe at (null when nothing is running).
 */
public record PreviewStatusResponse(
        boolean running,
        String command,
        String url
) {
}
