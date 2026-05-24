package com.codeleon.runner;

public record ProjectRunDetection(
        boolean runnable,
        String environment,
        String command,
        boolean generatedEnvironment,
        String message
) {
    static ProjectRunDetection runnable(NixProjectRunnerService.ProjectRunSpec spec) {
        return new ProjectRunDetection(
                true,
                spec.environment().label(),
                spec.command(),
                !spec.projectFlake(),
                null
        );
    }

    static ProjectRunDetection notRunnable(String message) {
        return new ProjectRunDetection(false, null, null, false, message);
    }
}
