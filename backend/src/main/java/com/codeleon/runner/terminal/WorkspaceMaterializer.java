package com.codeleon.runner.terminal;

import com.codeleon.common.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes a room's in-memory files onto disk so a sandbox container can mount
 * them at /workspace. Mirrors the path-safety rules used by the Maven and Nix
 * runners (no absolute paths, no traversal, no drive letters, size caps).
 */
@Component
public class WorkspaceMaterializer {

    private static final int MAX_FILES = 250;
    private static final int MAX_TOTAL_BYTES = 750_000;

    /** A single file to drop into the workspace, as sent by the SPA. */
    public record FileEntry(String path, String text) {
    }

    public void materialize(Path workspace, List<FileEntry> files) throws IOException {
        if (files == null || files.isEmpty()) {
            return;
        }
        if (files.size() > MAX_FILES) {
            throw new BadRequestException("Workspace is limited to " + MAX_FILES + " files");
        }

        int totalBytes = 0;
        for (FileEntry file : files) {
            String text = file.text() == null ? "" : file.text();
            totalBytes += text.getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new BadRequestException("Workspace is too large to open a terminal");
            }
            writeFile(workspace, file.path(), text);
        }
    }

    private void writeFile(Path workspace, String rawPath, String text) throws IOException {
        String normalized = normalize(rawPath);
        if (normalized == null || normalized.isBlank()) {
            throw new BadRequestException("Invalid workspace file path");
        }
        Path relative = Path.of(normalized).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new BadRequestException("Invalid workspace file path: " + rawPath);
        }
        Path target = workspace.resolve(relative).normalize();
        if (!target.startsWith(workspace)) {
            throw new BadRequestException("Invalid workspace file path: " + rawPath);
        }

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }

    private String normalize(String rawPath) {
        if (rawPath == null) {
            return null;
        }
        String normalized = rawPath.replace('\\', '/').trim();
        if (normalized.isBlank()
                || normalized.startsWith("/")
                || normalized.contains("\0")
                || normalized.contains(":")
                || normalized.contains("//")) {
            return null;
        }
        return normalized;
    }
}
