package com.codeleon.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codeleon.runner")
public record CodeRunnerProperties(
        boolean enabled,
        String pythonImage,
        String javaImage,
        String mavenImage,
        String mavenCacheVolume,
        int timeoutMs,
        int javaTimeoutMs,
        int mavenTimeoutMs,
        int memoryMb,
        int mavenMemoryMb,
        double cpus,
        int pidsLimit,
        int maxOutputBytes,
        // Host-wide ceiling on concurrent sandbox runs (single-file, Maven
        // and Nix). Each run spawns a memory/CPU-capped `docker run`; this
        // bounds how many can stack up at once so a burst of run requests
        // cannot exhaust the VM. Enforced by RunnerConcurrencyGate.
        int maxConcurrentRuns,
        // Base directory under which the Maven and Nix runners create
        // per-run workspaces. Must resolve to the SAME absolute path on
        // both sides of the docker.sock bind mount in production — when
        // the backend creates "${workspaceBaseDir}/codeleon-…-XYZ" inside
        // its container, the daemon on the host has to find the exact
        // same path or its `-v` mount comes up empty. Defaults to /tmp
        // for local dev where the backend runs on the host directly.
        String workspaceBaseDir
) {
    public CodeRunnerProperties {
        if (pythonImage == null || pythonImage.isBlank()) pythonImage = "python:3.12-slim";
        if (javaImage == null || javaImage.isBlank()) javaImage = "eclipse-temurin:21-jdk";
        if (mavenImage == null || mavenImage.isBlank()) mavenImage = "maven:3.9-eclipse-temurin-21";
        if (mavenCacheVolume == null || mavenCacheVolume.isBlank()) mavenCacheVolume = "codeleon-maven-cache";
        if (timeoutMs <= 0) timeoutMs = 8_000;
        if (javaTimeoutMs <= 0) javaTimeoutMs = 25_000;
        if (mavenTimeoutMs <= 0) mavenTimeoutMs = 120_000;
        if (memoryMb <= 0) memoryMb = 256;
        if (mavenMemoryMb <= 0) mavenMemoryMb = 768;
        if (cpus <= 0) cpus = 0.5;
        if (pidsLimit <= 0) pidsLimit = 64;
        if (maxOutputBytes <= 0) maxOutputBytes = 64 * 1024;
        if (maxConcurrentRuns <= 0) maxConcurrentRuns = 3;
        if (workspaceBaseDir == null || workspaceBaseDir.isBlank()) workspaceBaseDir = "/tmp";
    }
}
