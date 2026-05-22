package com.codeleon.room.imports;

import com.codeleon.common.exception.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class GithubImportServiceTest {

    @Test
    void parsesHttpsRepositoryUrl() {
        GithubImportService.OwnerRepo or = GithubImportService.parseUrl("https://github.com/spring-projects/spring-boot");
        assertThat(or.owner()).isEqualTo("spring-projects");
        assertThat(or.repo()).isEqualTo("spring-boot");
    }

    @Test
    void parsesHttpsUrlWithDotGitSuffix() {
        GithubImportService.OwnerRepo or = GithubImportService.parseUrl("https://github.com/owner/repo.git");
        assertThat(or.owner()).isEqualTo("owner");
        assertThat(or.repo()).isEqualTo("repo");
    }

    @Test
    void parsesHttpsUrlWithTrailingSlash() {
        GithubImportService.OwnerRepo or = GithubImportService.parseUrl("https://github.com/owner/repo/");
        assertThat(or.repo()).isEqualTo("repo");
    }

    @Test
    void parsesShorthandUrl() {
        GithubImportService.OwnerRepo or = GithubImportService.parseUrl("torvalds/linux");
        assertThat(or.owner()).isEqualTo("torvalds");
        assertThat(or.repo()).isEqualTo("linux");
    }

    @Test
    void rejectsInvalidUrl() {
        assertThatThrownBy(() -> GithubImportService.parseUrl("not a url"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> GithubImportService.parseUrl(""))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> GithubImportService.parseUrl(null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void stripTopFolderHandlesGithubArchiveLayout() {
        // GitHub zip entries look like "repo-main/src/Main.java".
        assertThat(GithubImportService.stripTopFolder("repo-main/src/Main.java"))
                .isEqualTo("src/Main.java");
        assertThat(GithubImportService.stripTopFolder("repo-master/README.md"))
                .isEqualTo("README.md");
        assertThat(GithubImportService.stripTopFolder("repo/")).isEqualTo("");
        assertThat(GithubImportService.stripTopFolder(null)).isNull();
    }

    @Test
    void containsSkippedDirectoryFiltersBuildOutput() {
        assertThat(GithubImportService.containsSkippedDirectory("node_modules/lodash/index.js")).isTrue();
        assertThat(GithubImportService.containsSkippedDirectory("frontend/dist/main.js")).isTrue();
        assertThat(GithubImportService.containsSkippedDirectory(".git/config")).isTrue();
        assertThat(GithubImportService.containsSkippedDirectory("src/Main.java")).isFalse();
        assertThat(GithubImportService.containsSkippedDirectory("readme.md")).isFalse();
    }

    @Test
    void isLikelyTextFileMatchesExtensionsAndConventionalNames() {
        assertThat(GithubImportService.isLikelyTextFile("App.java")).isTrue();
        assertThat(GithubImportService.isLikelyTextFile("script.py")).isTrue();
        assertThat(GithubImportService.isLikelyTextFile("Dockerfile")).isTrue();
        assertThat(GithubImportService.isLikelyTextFile("path/to/.env.example")).isTrue();
        assertThat(GithubImportService.isLikelyTextFile("logo.png")).isFalse();
        assertThat(GithubImportService.isLikelyTextFile("video.mp4")).isFalse();
        assertThat(GithubImportService.isLikelyTextFile("noext")).isFalse();
    }

    @Test
    void parsesGithubRepositoryPage() throws Exception {
        GithubImportService service = new GithubImportService(
                mock(com.codeleon.room.RoomFileService.class),
                mock(com.codeleon.auth.oauth.OAuthAccountRepository.class),
                new ObjectMapper()
        );

        var repos = service.parseRepositoryPage("""
                [
                  {
                    "full_name": "octocat/hello-world",
                    "name": "hello-world",
                    "html_url": "https://github.com/octocat/hello-world",
                    "default_branch": "main",
                    "private": true,
                    "description": "Demo repo",
                    "updated_at": "2026-05-22T10:15:30Z",
                    "owner": { "login": "octocat" }
                  }
                ]
                """);

        assertThat(repos).hasSize(1);
        assertThat(repos.get(0).fullName()).isEqualTo("octocat/hello-world");
        assertThat(repos.get(0).owner()).isEqualTo("octocat");
        assertThat(repos.get(0).privateRepo()).isTrue();
        assertThat(repos.get(0).defaultBranch()).isEqualTo("main");
    }
}
