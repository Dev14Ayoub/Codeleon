package com.codeleon.room.imports;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GithubImportRequest(
        @NotBlank
        @Size(max = 500)
        String repoUrl,

        @Size(max = 100)
        String branch
) {
}
