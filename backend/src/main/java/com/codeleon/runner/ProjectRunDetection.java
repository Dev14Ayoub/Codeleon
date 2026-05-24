package com.codeleon.runner;

import java.util.List;

public record ProjectRunDetection(
        boolean runnable,
        String environment,
        String command,
        boolean generatedEnvironment,
        List<String> services,
        String message
) {
    static ProjectRunDetection runnable(NixProjectRunnerService.ProjectRunSpec spec) {
        return new ProjectRunDetection(
                true,
                spec.environment().label(),
                spec.command(),
                !spec.projectFlake(),
                spec.services(),
                null
        );
    }

    static ProjectRunDetection notRunnable(String message) {
        return new ProjectRunDetection(false, null, null, false, List.of(), message);
    }
}
