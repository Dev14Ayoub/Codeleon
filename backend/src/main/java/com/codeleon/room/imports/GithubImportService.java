package com.codeleon.room.imports;

import com.codeleon.common.exception.BadRequestException;
import com.codeleon.room.RoomFile;
import com.codeleon.room.RoomFileService;
import com.codeleon.user.User;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imports a public GitHub repository into a Codeleon room. Downloads the
 * repository as a ZIP from github.com, walks every entry, filters to text
 * files within size and count limits, creates a {@link RoomFile} per kept
 * entry and returns the contents so the frontend can seed the Y.Doc.
 *
 * <p>Anonymous fetches only — covers public repos within the GitHub rate
 * limit. PAT support is intentionally out of scope for now.</p>
 */
@Service
@RequiredArgsConstructor
public class GithubImportService {

    private static final Logger log = LoggerFactory.getLogger(GithubImportService.class);

    static final int MAX_FILES = 200;
    static final int MAX_FILE_BYTES = 100 * 1024;

    private static final Pattern HTTPS_URL_PATTERN =
            Pattern.compile("^https?://github\\.com/([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?$");
    private static final Pattern SHORTHAND_PATTERN =
            Pattern.compile("^([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?$");
    private static final Pattern PATH_PATTERN =
            Pattern.compile("^[A-Za-z0-9._-][A-Za-z0-9._/ -]*$");

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            "node_modules", ".git", ".idea", ".vscode", "dist", "build",
            "target", "out", ".next", ".nuxt", "__pycache__", ".pytest_cache",
            "venv", ".venv", ".mvn"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "java", "py", "js", "mjs", "cjs", "ts", "jsx", "tsx",
            "html", "htm", "css", "scss", "json", "yml", "yaml", "xml",
            "md", "markdown", "sh", "bash", "sql", "go", "rs", "rb",
            "php", "cpp", "cxx", "cc", "hpp", "h", "c", "cs", "kt",
            "kts", "swift", "dockerfile", "txt", "log", "env", "ini",
            "toml", "gradle", "properties", "gitignore", "editorconfig",
            "lock"
    );

    private static final Set<String> TEXT_BASENAMES = Set.of(
            "dockerfile", "makefile", "readme", "license"
    );

    private final RoomFileService roomFileService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public GithubImportResponse importRepo(UUID roomId, User user, String repoUrl, String requestedBranch) {
        OwnerRepo or = parseUrl(repoUrl);
        String preferredBranch = (requestedBranch == null || requestedBranch.isBlank())
                ? "main"
                : requestedBranch.trim();

        FetchResult fetch = downloadZipWithFallback(or, preferredBranch);

        List<GithubImportResponse.ImportedFile> imported = new ArrayList<>();
        List<GithubImportResponse.SkippedFile> skipped = new ArrayList<>();
        boolean truncated = false;

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(fetch.bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (imported.size() >= MAX_FILES) {
                    truncated = true;
                    break;
                }
                if (entry.isDirectory()) continue;

                String cleaned = stripTopFolder(entry.getName());
                if (cleaned == null || cleaned.isBlank()) continue;

                if (cleaned.length() > 255) {
                    skipped.add(new GithubImportResponse.SkippedFile(cleaned, "path too long"));
                    continue;
                }
                if (containsSkippedDirectory(cleaned)) {
                    skipped.add(new GithubImportResponse.SkippedFile(cleaned, "ignored directory"));
                    continue;
                }
                if (!PATH_PATTERN.matcher(cleaned).matches()) {
                    skipped.add(new GithubImportResponse.SkippedFile(cleaned, "invalid characters"));
                    continue;
                }
                if (!isLikelyTextFile(cleaned)) {
                    skipped.add(new GithubImportResponse.SkippedFile(cleaned, "non-text"));
                    continue;
                }

                long announced = entry.getSize();
                if (announced > MAX_FILE_BYTES) {
                    skipped.add(new GithubImportResponse.SkippedFile(
                            cleaned, "larger than " + (MAX_FILE_BYTES / 1024) + " KB"));
                    continue;
                }

                byte[] bytes = readBoundedEntry(zis, MAX_FILE_BYTES);
                if (bytes == null) {
                    skipped.add(new GithubImportResponse.SkippedFile(
                            cleaned, "larger than " + (MAX_FILE_BYTES / 1024) + " KB"));
                    continue;
                }

                try {
                    RoomFile created = roomFileService.createFile(roomId, user, cleaned);
                    imported.add(new GithubImportResponse.ImportedFile(
                            created.getId(),
                            created.getPath(),
                            created.getLanguage(),
                            new String(bytes, StandardCharsets.UTF_8)
                    ));
                } catch (BadRequestException ex) {
                    skipped.add(new GithubImportResponse.SkippedFile(cleaned, ex.getMessage()));
                }
            }
        } catch (IOException ex) {
            throw new BadRequestException("Failed to extract GitHub archive: " + ex.getMessage());
        }

        log.info("Imported {} files from {}/{} ({}); skipped {}, truncated={}",
                imported.size(), or.owner(), or.repo(), fetch.branch, skipped.size(), truncated);

        return new GithubImportResponse(
                or.owner(), or.repo(), fetch.branch, truncated, imported, skipped
        );
    }

    // ---------------------------------------------------------------------
    // URL parsing.
    // ---------------------------------------------------------------------

    static OwnerRepo parseUrl(String repoUrl) {
        if (repoUrl == null) {
            throw new BadRequestException("Repository URL is required");
        }
        String trimmed = repoUrl.trim();
        Matcher https = HTTPS_URL_PATTERN.matcher(trimmed);
        if (https.matches()) {
            return new OwnerRepo(https.group(1), https.group(2));
        }
        Matcher shorthand = SHORTHAND_PATTERN.matcher(trimmed);
        if (shorthand.matches()) {
            return new OwnerRepo(shorthand.group(1), shorthand.group(2));
        }
        throw new BadRequestException(
                "Could not parse repository URL. Expected formats: " +
                "https://github.com/owner/repo or owner/repo"
        );
    }

    record OwnerRepo(String owner, String repo) {
    }

    // ---------------------------------------------------------------------
    // ZIP download with main → master fallback.
    // ---------------------------------------------------------------------

    private FetchResult downloadZipWithFallback(OwnerRepo or, String preferredBranch) {
        try {
            return new FetchResult(downloadZip(or, preferredBranch), preferredBranch);
        } catch (BranchNotFoundException ex) {
            // GitHub returns 404 when the branch does not exist. If the user
            // accepted the default we transparently retry against master.
            if ("main".equals(preferredBranch)) {
                return new FetchResult(downloadZip(or, "master"), "master");
            }
            throw new BadRequestException(
                    "Branch '" + preferredBranch + "' not found in " + or.owner() + "/" + or.repo()
            );
        }
    }

    private byte[] downloadZip(OwnerRepo or, String branch) {
        String url = String.format(
                "https://github.com/%s/%s/archive/refs/heads/%s.zip",
                or.owner(), or.repo(), branch
        );
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int code = response.statusCode();
            if (code == 404) {
                throw new BranchNotFoundException();
            }
            if (code / 100 != 2) {
                throw new BadRequestException("GitHub returned HTTP " + code + " when fetching the archive");
            }
            return response.body();
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new BadRequestException("Could not reach github.com: " + ex.getMessage());
        }
    }

    private record FetchResult(byte[] bytes, String branch) {
    }

    /** Internal sentinel — branch did not exist, try the fallback. */
    private static final class BranchNotFoundException extends RuntimeException {
    }

    // ---------------------------------------------------------------------
    // Path & content filters (mirror frontend's prepareLocalImport).
    // ---------------------------------------------------------------------

    static String stripTopFolder(String rawPath) {
        if (rawPath == null) return null;
        String normalized = rawPath.replace('\\', '/');
        if (normalized.startsWith("./")) normalized = normalized.substring(2);
        int idx = normalized.indexOf('/');
        return idx < 0 ? normalized : normalized.substring(idx + 1);
    }

    static boolean containsSkippedDirectory(String path) {
        for (String segment : path.split("/")) {
            if (!segment.isEmpty() && SKIPPED_DIRECTORIES.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    static boolean isLikelyTextFile(String path) {
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot < path.length() - 1) {
            String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (TEXT_EXTENSIONS.contains(ext)) return true;
        }
        int slash = path.lastIndexOf('/');
        String base = (slash < 0 ? path : path.substring(slash + 1)).toLowerCase(Locale.ROOT);
        if (TEXT_BASENAMES.contains(base)) return true;
        return base.startsWith(".env");
    }

    /**
     * Reads at most {@code limit} bytes from the current ZIP entry. Returns
     * {@code null} when the entry overflows the limit so the caller can
     * report "too large" without buffering the whole thing.
     */
    private static byte[] readBoundedEntry(ZipInputStream zis, int limit) throws IOException {
        byte[] buffer = new byte[8 * 1024];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int total = 0;
        int n;
        while ((n = zis.read(buffer)) > 0) {
            total += n;
            if (total > limit) return null;
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
