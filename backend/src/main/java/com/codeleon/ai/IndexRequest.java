package com.codeleon.ai;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record IndexRequest(
        String path,
        @NotNull @Size(max = 200_000) String text
) {
    public String pathOrDefault() {
        return (path == null || path.isBlank()) ? "main" : path;
    }
}
