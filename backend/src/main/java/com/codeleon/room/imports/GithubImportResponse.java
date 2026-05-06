package com.codeleon.room.imports;

import java.util.List;
import java.util.UUID;

public record GithubImportResponse(
        String owner,
        String repo,
        String branchUsed,
        boolean truncated,
        List<ImportedFile> imported,
        List<SkippedFile> skipped
) {
    public record ImportedFile(
            UUID fileId,
            String path,
            String language,
            String content
    ) {
    }

    public record SkippedFile(
            String path,
            String reason
    ) {
    }
}
