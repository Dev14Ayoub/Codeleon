package com.codeleon.runner;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ProjectRunRequest(
        @Size(max = 300) String command,
        @Valid List<RunRequest.RunFile> files
) {
}
