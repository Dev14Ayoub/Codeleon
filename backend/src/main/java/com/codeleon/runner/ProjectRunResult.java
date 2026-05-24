package com.codeleon.runner;

import java.util.ArrayList;
import java.util.List;

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
        int timeoutMs,
        String runnerImage,
        List<String> cacheVolumes
) {
    static ProjectRunResult from(RunResult result, NixProjectRunnerService.ProjectRunSpec spec, int fileCount, NixRunnerProperties props) {
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
                props.timeoutMs(),
                props.image(),
                cacheVolumes(spec.environment(), props)
        );
    }

    private static List<String> cacheVolumes(NixProjectRunnerService.ProjectEnvironment environment, NixRunnerProperties props) {
        List<String> volumes = new ArrayList<>();
        volumes.add(props.cacheVolume());
        if (environment == NixProjectRunnerService.ProjectEnvironment.JAVA_MAVEN
                || environment == NixProjectRunnerService.ProjectEnvironment.NIX_FLAKE) {
            volumes.add(props.mavenCacheVolume());
        }
        if (environment == NixProjectRunnerService.ProjectEnvironment.NODE
                || environment == NixProjectRunnerService.ProjectEnvironment.NIX_FLAKE) {
            volumes.add(props.npmCacheVolume());
        }
        if (environment == NixProjectRunnerService.ProjectEnvironment.PYTHON
                || environment == NixProjectRunnerService.ProjectEnvironment.NIX_FLAKE) {
            volumes.add(props.pipCacheVolume());
        }
        return List.copyOf(volumes);
    }
}
