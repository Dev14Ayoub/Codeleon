package com.codeleon.runner;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "codeleon.runner.nix")
public record NixRunnerProperties(
        boolean enabled,
        String image,
        String cacheVolume,
        String mavenCacheVolume,
        String npmCacheVolume,
        String pipCacheVolume,
        int timeoutMs,
        int memoryMb,
        double cpus,
        int pidsLimit,
        int maxOutputBytes
) {
    public NixRunnerProperties {
        if (image == null || image.isBlank()) image = "nixos/nix:2.24.11";
        if (cacheVolume == null || cacheVolume.isBlank()) cacheVolume = "codeleon-nix-store";
        if (mavenCacheVolume == null || mavenCacheVolume.isBlank()) mavenCacheVolume = "codeleon-maven-cache";
        if (npmCacheVolume == null || npmCacheVolume.isBlank()) npmCacheVolume = "codeleon-npm-cache";
        if (pipCacheVolume == null || pipCacheVolume.isBlank()) pipCacheVolume = "codeleon-pip-cache";
        if (timeoutMs <= 0) timeoutMs = 180_000;
        if (memoryMb <= 0) memoryMb = 1024;
        if (cpus <= 0) cpus = 1.0;
        if (pidsLimit <= 0) pidsLimit = 128;
        if (maxOutputBytes <= 0) maxOutputBytes = 128 * 1024;
    }
}
