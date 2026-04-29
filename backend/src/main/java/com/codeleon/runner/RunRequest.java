package com.codeleon.runner;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RunRequest(
        @NotNull RunLanguage language,
        @NotNull @Size(max = 100_000) String code,
        @Size(max = 10_000) String stdin
) {
}
