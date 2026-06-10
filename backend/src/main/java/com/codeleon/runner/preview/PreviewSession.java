package com.codeleon.runner.preview;

import java.nio.file.Path;
import java.util.UUID;

/**
 * Live state for one room's web preview: the detached dev-server container, the
 * port it is expected to bind, the on-disk workspace, and activity timestamps
 * the reaper uses. Keyed by room (one preview per room).
 */
public class PreviewSession {

    private final UUID roomId;
    private final String containerName;
    private final int port;
    private final Path workspace;
    private final String command;
    private final long startedAt;
    private volatile long lastActivityAt;

    public PreviewSession(UUID roomId, String containerName, int port, Path workspace, String command) {
        this.roomId = roomId;
        this.containerName = containerName;
        this.port = port;
        this.workspace = workspace;
        this.command = command;
        this.startedAt = System.currentTimeMillis();
        this.lastActivityAt = this.startedAt;
    }

    public UUID roomId() {
        return roomId;
    }

    public String containerName() {
        return containerName;
    }

    public int port() {
        return port;
    }

    public Path workspace() {
        return workspace;
    }

    public String command() {
        return command;
    }

    public long startedAt() {
        return startedAt;
    }

    public long lastActivityAt() {
        return lastActivityAt;
    }

    /** Marks the preview as recently used so the idle reaper leaves it alone. */
    public void touch() {
        this.lastActivityAt = System.currentTimeMillis();
    }
}
