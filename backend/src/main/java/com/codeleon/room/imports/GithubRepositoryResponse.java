package com.codeleon.room.imports;

import java.time.Instant;

public record GithubRepositoryResponse(
        String fullName,
        String owner,
        String name,
        String htmlUrl,
        String defaultBranch,
        boolean privateRepo,
        String description,
        Instant updatedAt
) {
}
