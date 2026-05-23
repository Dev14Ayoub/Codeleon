package com.codeleon.room.imports;

import com.codeleon.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class GithubImportController {

    private final GithubImportService githubImportService;

    @PostMapping("/rooms/{roomId}/import/github")
    public GithubImportResponse importGithub(
            @PathVariable UUID roomId,
            @AuthenticationPrincipal User user,
            @Valid @RequestBody GithubImportRequest request
    ) {
        return githubImportService.importRepo(roomId, user, request.repoUrl(), request.branch());
    }

    @GetMapping("/github/repositories")
    public List<GithubRepositoryResponse> githubRepositories(
            @AuthenticationPrincipal User user
    ) {
        return githubImportService.listConnectedRepositories(user);
    }
}
