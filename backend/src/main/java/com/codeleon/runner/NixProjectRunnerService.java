package com.codeleon.runner;

import com.codeleon.common.exception.BadRequestException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
@EnableConfigurationProperties(NixRunnerProperties.class)
public class NixProjectRunnerService {

    private static final Logger log = LoggerFactory.getLogger(NixProjectRunnerService.class);
    private static final int MAX_PROJECT_FILES = 300;
    private static final int MAX_PROJECT_BYTES = 1_500_000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final NixRunnerProperties props;

    public NixProjectRunnerService(NixRunnerProperties props) {
        this.props = props;
    }

    public ProjectRunResult run(ProjectRunRequest request) {
        if (!props.enabled()) {
            throw new BadRequestException("Nix project runner is disabled on this server");
        }

        ProjectRunSpec spec = detect(request);
        int fileCount = request.files() == null ? 0 : request.files().size();
        Path workspace = null;
        String containerName = "codeleon-nix-runner-" + UUID.randomUUID();
        try {
            workspace = Files.createTempDirectory("codeleon-nix-run-");
            materializeProject(request.files(), workspace);
            if (!spec.projectFlake()) {
                Files.writeString(workspace.resolve("flake.nix"), generatedFlake(spec.environment()), StandardCharsets.UTF_8);
                Files.writeString(workspace.resolve("flake.lock"), generatedFlakeLock(), StandardCharsets.UTF_8);
            }

            List<String> command = buildDockerCommand(containerName, workspace, spec.command());
            RunResult result = execute(command, containerName, props.timeoutMs());
            return ProjectRunResult.from(result, spec, fileCount, props.timeoutMs());
        } catch (IOException ex) {
            log.error("Failed to prepare Nix workspace", ex);
            throw new BadRequestException("Could not prepare Nix project: " + ex.getMessage());
        } finally {
            deleteWorkspace(workspace);
        }
    }

    public ProjectRunDetection detectRunnable(ProjectRunRequest request) {
        if (!props.enabled()) {
            return ProjectRunDetection.notRunnable("Nix project runner is disabled on this server");
        }
        try {
            return ProjectRunDetection.runnable(detect(request));
        } catch (BadRequestException ex) {
            return ProjectRunDetection.notRunnable(ex.getMessage());
        }
    }

    ProjectRunSpec detect(ProjectRunRequest request) {
        List<RunRequest.RunFile> files = request.files() == null ? List.of() : request.files();
        if (files.isEmpty()) {
            throw new BadRequestException("Project runner needs at least one file");
        }

        String command = normalizeCommand(request.command());
        if (hasPath(files, "flake.nix")) {
            return new ProjectRunSpec(ProjectEnvironment.NIX_FLAKE, true, command == null ? "true" : command);
        }
        if (hasPath(files, "pom.xml")) {
            return new ProjectRunSpec(ProjectEnvironment.JAVA_MAVEN, false, command == null ? "mvn test" : command);
        }
        if (hasPath(files, "package.json")) {
            return new ProjectRunSpec(ProjectEnvironment.NODE, false, command == null ? defaultNodeCommand(files) : command);
        }
        if (hasPath(files, "requirements.txt") || hasPath(files, "pyproject.toml")) {
            return new ProjectRunSpec(ProjectEnvironment.PYTHON, false, command == null ? defaultPythonCommand(files) : command);
        }
        throw new BadRequestException("No Nix-compatible project environment detected");
    }

    String generatedFlake(ProjectEnvironment environment) {
        String packages = switch (environment) {
            case JAVA_MAVEN -> "jdk21_headless maven";
            case NODE -> "nodejs_20 python312 gnumake gcc pkg-config";
            case PYTHON -> "python312 python312Packages.pip python312Packages.pytest gcc pkg-config";
            case NIX_FLAKE -> "";
        };
        return """
                {
                  description = "Codeleon generated project environment";
                  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.11";
                  outputs = { nixpkgs, ... }:
                    let
                      systems = [ "x86_64-linux" "aarch64-linux" ];
                      forAllSystems = nixpkgs.lib.genAttrs systems;
                    in {
                      devShells = forAllSystems (system:
                        let
                          pkgs = import nixpkgs { inherit system; };
                        in {
                          default = pkgs.mkShell {
                            packages = with pkgs; [ %s ];
                          };
                        });
                    };
                }
                """.formatted(packages);
    }

    String generatedFlakeLock() {
        return """
                {
                  "nodes": {
                    "nixpkgs": {
                      "locked": {
                        "lastModified": 1751274312,
                        "narHash": "sha256-/bVBlRpECLVzjV19t5KMdMFWSwKLtb5RyXdjz3LJT+g=",
                        "owner": "NixOS",
                        "repo": "nixpkgs",
                        "rev": "50ab793786d9de88ee30ec4e4c24fb4236fc2674",
                        "type": "github"
                      },
                      "original": {
                        "owner": "NixOS",
                        "ref": "nixos-24.11",
                        "repo": "nixpkgs",
                        "type": "github"
                      }
                    },
                    "root": {
                      "inputs": {
                        "nixpkgs": "nixpkgs"
                      }
                    }
                  },
                  "root": "root",
                  "version": 7
                }
                """;
    }

    List<String> buildDockerCommand(String containerName, Path workspace, String projectCommand) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("--name");
        cmd.add(containerName);
        cmd.add("--memory=" + props.memoryMb() + "m");
        cmd.add("--memory-swap=" + props.memoryMb() + "m");
        cmd.add("--cpus=" + props.cpus());
        cmd.add("--pids-limit=" + props.pidsLimit());
        cmd.add("--security-opt=no-new-privileges");
        cmd.add("-v");
        cmd.add(workspace.toAbsolutePath() + ":/workspace");
        cmd.add("-v");
        cmd.add(props.cacheVolume() + ":/nix");
        cmd.add("--env=CODELEON_PROJECT_COMMAND=" + projectCommand);
        cmd.add("--workdir=/workspace");
        cmd.add(props.image());
        cmd.add("sh");
        cmd.add("-lc");
        cmd.add("nix --extra-experimental-features 'nix-command flakes' develop --command sh -lc \"$CODELEON_PROJECT_COMMAND\"");
        return cmd;
    }

    private void materializeProject(List<RunRequest.RunFile> files, Path workspace) throws IOException {
        if (files.size() > MAX_PROJECT_FILES) {
            throw new BadRequestException("Nix projects are limited to " + MAX_PROJECT_FILES + " files for now");
        }

        int totalBytes = 0;
        for (RunRequest.RunFile file : files) {
            String text = file.text() == null ? "" : file.text();
            totalBytes += text.getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes > MAX_PROJECT_BYTES) {
                throw new BadRequestException("Nix project is too large to run in the sandbox");
            }
            writeWorkspaceFile(workspace, file.path(), text);
        }
    }

    private void writeWorkspaceFile(Path workspace, String rawPath, String text) throws IOException {
        String normalized = normalizeRunPath(rawPath);
        if (normalized == null || normalized.isBlank()) {
            throw new BadRequestException("Invalid project file path");
        }
        Path relative = Path.of(normalized).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new BadRequestException("Invalid project file path: " + rawPath);
        }

        Path target = workspace.resolve(relative).normalize();
        if (!target.startsWith(workspace)) {
            throw new BadRequestException("Invalid project file path: " + rawPath);
        }

        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, text == null ? "" : text, StandardCharsets.UTF_8);
    }

    private static String defaultNodeCommand(List<RunRequest.RunFile> files) {
        String packageJson = findText(files, "package.json");
        String installCommand = hasPath(files, "package-lock.json") ? "npm ci" : "npm install";
        if (packageJson != null) {
            try {
                JsonNode scripts = OBJECT_MAPPER.readTree(packageJson).path("scripts");
                if (scripts.hasNonNull("test")) {
                    return installCommand + " && npm test";
                }
                if (scripts.hasNonNull("build")) {
                    return installCommand + " && npm run build";
                }
            } catch (IOException ex) {
                throw new BadRequestException("package.json is not valid JSON");
            }
        }
        return installCommand;
    }

    private static String defaultPythonCommand(List<RunRequest.RunFile> files) {
        String dependencyInstall = pythonDependencyInstallCommand(files);
        boolean hasTests = files.stream()
                .map(file -> normalizeRunPathForCompare(file.path()))
                .anyMatch(path -> path != null
                        && (path.startsWith("test_")
                        || path.contains("/test_")
                        || path.startsWith("tests/")));
        if (hasTests) {
            return withDependencyInstall(dependencyInstall, "pytest");
        }
        if (hasPath(files, "main.py")) {
            return withDependencyInstall(dependencyInstall, "python main.py");
        }
        return withDependencyInstall(dependencyInstall, "python -m py_compile $(find . -name '*.py' -type f)");
    }

    private static String pythonDependencyInstallCommand(List<RunRequest.RunFile> files) {
        if (hasPath(files, "requirements.txt")) {
            return "python -m pip install -r requirements.txt";
        }
        String pyproject = findText(files, "pyproject.toml");
        if (pyproject != null && (pyproject.contains("[project]") || pyproject.contains("[build-system]"))) {
            return "python -m pip install .";
        }
        return null;
    }

    private static String withDependencyInstall(String installCommand, String runCommand) {
        return installCommand == null ? runCommand : installCommand + " && " + runCommand;
    }

    private static String normalizeCommand(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String trimmed = command.trim();
        if (trimmed.length() > 300 || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new BadRequestException("Invalid project command");
        }
        return trimmed;
    }

    private static boolean hasPath(List<RunRequest.RunFile> files, String path) {
        return files.stream().anyMatch(file -> path.equals(normalizeRunPathForCompare(file.path())));
    }

    private static String findText(List<RunRequest.RunFile> files, String path) {
        return files.stream()
                .filter(file -> path.equals(normalizeRunPathForCompare(file.path())))
                .findFirst()
                .map(RunRequest.RunFile::text)
                .orElse(null);
    }

    private static String normalizeRunPathForCompare(String rawPath) {
        String normalized = normalizeRunPath(rawPath);
        return normalized == null ? null : normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private static String normalizeRunPath(String rawPath) {
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

    private RunResult execute(List<String> command, String containerName, int timeoutMs) {
        long start = System.currentTimeMillis();
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException ex) {
            log.error("Failed to spawn docker process", ex);
            throw new BadRequestException("Nix runner unavailable: " + ex.getMessage());
        }

        CompletableFuture<String> stdoutFuture = readAsync(process.getInputStream());
        CompletableFuture<String> stderrFuture = readAsync(process.getErrorStream());

        boolean finished;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            killContainer(containerName);
            return new RunResult("", "Execution interrupted", -1, System.currentTimeMillis() - start, true);
        }

        boolean timedOut = false;
        if (!finished) {
            killContainer(containerName);
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
            timedOut = true;
        }

        String stdout = awaitOutput(stdoutFuture);
        String stderr = awaitOutput(stderrFuture);
        int exitCode = finished ? process.exitValue() : -1;
        long duration = System.currentTimeMillis() - start;
        return new RunResult(stdout, stderr, exitCode, duration, timedOut);
    }

    private CompletableFuture<String> readAsync(InputStream stream) {
        int limit = props.maxOutputBytes();
        return CompletableFuture.supplyAsync(() -> {
            byte[] buffer = new byte[4096];
            byte[] capture = new byte[limit];
            int total = 0;
            try {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    int copy = Math.min(read, limit - total);
                    if (copy > 0) {
                        System.arraycopy(buffer, 0, capture, total, copy);
                        total += copy;
                    }
                }
            } catch (IOException ex) {
                log.debug("Stream read interrupted", ex);
            }
            return new String(capture, 0, total, StandardCharsets.UTF_8);
        });
    }

    private String awaitOutput(CompletableFuture<String> future) {
        try {
            return future.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    private void killContainer(String name) {
        try {
            new ProcessBuilder("docker", "kill", name).start().waitFor(3, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to kill Nix runner container {}", name, ex);
        }
    }

    private void deleteWorkspace(Path workspace) {
        if (workspace == null) {
            return;
        }
        try (var paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.debug("Failed to delete Nix runner workspace {}", path, ex);
                }
            });
        } catch (IOException ex) {
            log.debug("Failed to clean Nix runner workspace {}", workspace, ex);
        }
    }

    enum ProjectEnvironment {
        NIX_FLAKE("Nix flake"),
        JAVA_MAVEN("Generated Java/Maven"),
        NODE("Generated Node"),
        PYTHON("Generated Python");

        private final String label;

        ProjectEnvironment(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record ProjectRunSpec(ProjectEnvironment environment, boolean projectFlake, String command) {
    }
}
