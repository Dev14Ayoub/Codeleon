package com.codeleon.runner;

public record RunResult(
        String stdout,
        String stderr,
        int exitCode,
        long durationMs,
        boolean timedOut
) {
}
