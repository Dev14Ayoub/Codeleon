package com.codeleon.room.imports;

import com.codeleon.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
