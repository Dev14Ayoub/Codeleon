package com.codeleon.runner.preview;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for the live web preview. A preview is a long-lived dev-server
 * container the backend reverse-proxies into the editor. Unlike the batch
 * runners and the terminal, it has network access, so it runs on a dedicated
 * isolated Docker network (see docker-compose.prod.yml) and is capped tightly:
 * the 8 GB production VM also hosts the AI model, so a single preview at a time.
 */
@ConfigurationProperties(prefix = "codeleon.runner.preview")
public record PreviewProperties(
        boolean enabled,
        String image,
        String network,
        int port,
        int memoryMb,
        double cpus,
        int pidsLimit,
        long idleTimeoutMs,
        long maxSessionMs,
        int maxConcurrentSessions,
        long startupTimeoutMs
) {
    public PreviewProperties {
        // node:20 is Debian-based: bash + node + npm, covering the common web
        // case (Vite/React, Express). Swap via RUNNER_PREVIEW_IMAGE for other
        // runtimes (e.g. a polyglot or Nix image).
        if (image == null || image.isBlank()) image = "node:20";
        // The compose project prefix makes the real network `codeleon_preview`.
        if (network == null || network.isBlank()) network = "codeleon_preview";
        if (port <= 0) port = 8000;
        if (memoryMb <= 0) memoryMb = 768;
        if (cpus <= 0) cpus = 1.0;
        if (pidsLimit <= 0) pidsLimit = 256;
        if (idleTimeoutMs <= 0) idleTimeoutMs = 600_000;      // 10 min idle
        if (maxSessionMs <= 0) maxSessionMs = 1_800_000;      // 30 min hard cap
        if (maxConcurrentSessions <= 0) maxConcurrentSessions = 1;
        if (startupTimeoutMs <= 0) startupTimeoutMs = 60_000; // wait for the port
    }
}
