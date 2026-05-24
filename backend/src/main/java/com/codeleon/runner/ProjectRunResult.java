package com.codeleon.runner;

public record ProjectRunResult(
        String stdout,
        String stderr,
        int exitCode,
        long durationMs,
        boolean timedOut,
        String environment,
        String command,
        boolean generatedEnvironment,
        int fileCount,
        int timeoutMs
) {
    static ProjectRunResult from(RunResult result, NixProjectRunnerService.ProjectRunSpec spec, int fileCount, int timeoutMs) {
        return new ProjectRunResult(
                result.stdout(),
                result.stderr(),
                result.exitCode(),
                result.durationMs(),
                result.timedOut(),
                spec.environment().label(),
                spec.command(),
                !spec.projectFlake(),
                fileCount,
                timeoutMs
        );
    }
}
