package com.codeleon.runner.terminal;

import java.nio.file.Path;

/**
 * Live state for one interactive terminal: its Docker process, the on-disk
 * workspace mounted into the container, and activity timestamps the reaper
 * uses to enforce idle and wall-clock limits.
 */
public class TerminalSession {

    private final String id;
    private final Process process;
    private final String containerName;
    private final Path workspace;
    private final long startedAt;
    private volatile long lastActivityAt;

    public TerminalSession(String id, Process process, String containerName, Path workspace) {
        this.id = id;
        this.process = process;
        this.containerName = containerName;
        this.workspace = workspace;
        this.startedAt = System.currentTimeMillis();
        this.lastActivityAt = this.startedAt;
    }

    public String id() {
        return id;
    }

    public Process process() {
        return process;
    }

    public String containerName() {
        return containerName;
    }

    public Path workspace() {
        return workspace;
    }

    public long startedAt() {
        return startedAt;
    }

    public long lastActivityAt() {
        return lastActivityAt;
    }

    /** Marks the session as recently active so the idle reaper leaves it alone. */
    public void touch() {
        this.lastActivityAt = System.currentTimeMillis();
    }
}
