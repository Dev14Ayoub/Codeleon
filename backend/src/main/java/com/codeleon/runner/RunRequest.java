package com.codeleon.runner;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

import java.util.List;

public record RunRequest(
        @NotNull RunLanguage language,
        @NotNull @Size(max = 100_000) String code,
        @Size(max = 255) String filename,
        @Size(max = 10_000) String stdin,
        List<@Valid RunFile> files
) {
    public record RunFile(
            @NotNull @Size(max = 255) String path,
            @NotNull @Size(max = 100_000) String text
    ) {
    }
}
