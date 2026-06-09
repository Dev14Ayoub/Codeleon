package com.codeleon.runner.terminal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the interactive terminal sandbox. Defaults are deliberately
 * conservative because the production VM (8 GB) also hosts Ollama: a forgotten
 * terminal must not be able to pin RAM or CPU. Override per-field via the
 * RUNNER_TERMINAL_* environment variables wired in application.yml.
 */
@ConfigurationProperties(prefix = "codeleon.runner.terminal")
public record TerminalProperties(
        boolean enabled,
        String image,
        int memoryMb,
        double cpus,
        int pidsLimit,
        long idleTimeoutMs,
        long maxSessionMs,
        int maxConcurrentSessions,
        int maxOutputBytes
) {
    public TerminalProperties {
        // python:3.12-slim is Debian-based, so it ships bash AND python3 —
        // a sane default shell for a coding sandbox without a custom image.
        if (image == null || image.isBlank()) image = "python:3.12-slim";
        if (memoryMb <= 0) memoryMb = 512;
        if (cpus <= 0) cpus = 0.5;
        if (pidsLimit <= 0) pidsLimit = 128;
        if (idleTimeoutMs <= 0) idleTimeoutMs = 300_000;      // 5 min with no input
        if (maxSessionMs <= 0) maxSessionMs = 900_000;        // 15 min hard cap
        if (maxConcurrentSessions <= 0) maxConcurrentSessions = 3;
        if (maxOutputBytes <= 0) maxOutputBytes = 2 * 1024 * 1024; // per-session total
    }
}
