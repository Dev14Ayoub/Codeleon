package com.codeleon.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codeleon.runner")
public record CodeRunnerProperties(
        boolean enabled,
        String pythonImage,
        String javaImage,
        int timeoutMs,
        int javaTimeoutMs,
        int memoryMb,
        double cpus,
        int pidsLimit,
        int maxOutputBytes
) {
    public CodeRunnerProperties {
        if (pythonImage == null || pythonImage.isBlank()) pythonImage = "python:3.12-slim";
        if (javaImage == null || javaImage.isBlank()) javaImage = "eclipse-temurin:21-jdk";
        if (timeoutMs <= 0) timeoutMs = 8_000;
        if (javaTimeoutMs <= 0) javaTimeoutMs = 25_000;
        if (memoryMb <= 0) memoryMb = 256;
        if (cpus <= 0) cpus = 0.5;
        if (pidsLimit <= 0) pidsLimit = 64;
        if (maxOutputBytes <= 0) maxOutputBytes = 64 * 1024;
    }
}
