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
    // Shared with the Docker runner: where on disk per-run workspaces are
    // created. Must resolve to the same path host-side and container-side
    // so docker.sock-spawned siblings see what we just wrote.
    private final CodeRunnerProperties runnerProps;
    // Shared host-wide cap on concurrent sandbox runs (see RunnerConcurrencyGate).
    private final RunnerConcurrencyGate gate;

    public NixProjectRunnerService(NixRunnerProperties props, CodeRunnerProperties runnerProps, RunnerConcurrencyGate gate) {
        this.props = props;
        this.runnerProps = runnerProps;
        this.gate = gate;
    }

    public ProjectRunResult run(ProjectRunRequest request) {
        if (!props.enabled()) {
            throw new BadRequestException("Nix project runner is disabled on this server");
        }
        return gate.call(() -> runInternal(request));
    }

    private ProjectRunResult runInternal(ProjectRunRequest request) {
        ProjectRunSpec spec = detect(request);
        int fileCount = request.files() == null ? 0 : request.files().size();
        Path workspace = null;
        String containerName = "codeleon-nix-runner-" + UUID.randomUUID();
        String networkName = spec.services().isEmpty() ? null : "codeleon-nix-net-" + UUID.randomUUID();
        List<ServiceRuntime> serviceRuntimes = List.of();
        try {
            // See CodeRunnerProperties#workspaceBaseDir: same-path rule
            // on both sides of the docker.sock bind mount.
            Path baseDir = Path.of(runnerProps.workspaceBaseDir());
            Files.createDirectories(baseDir);
            workspace = Files.createTempDirectory(baseDir, "codeleon-nix-run-");
            materializeProject(request.files(), workspace);
            if (!spec.projectFlake()) {
                Files.writeString(workspace.resolve("flake.nix"), generatedFlake(spec.environment()), StandardCharsets.UTF_8);
                Files.writeString(workspace.resolve("flake.lock"), generatedFlakeLock(), StandardCharsets.UTF_8);
            }

            if (networkName != null) {
                createNetwork(networkName);
                serviceRuntimes = startServices(networkName, spec.services());
            }

            List<String> command = buildDockerCommand(containerName, workspace, spec, networkName, serviceRuntimes);
            RunResult result = execute(command, containerName, props.timeoutMs());
            return ProjectRunResult.from(result, spec, fileCount, props);
        } catch (IOException ex) {
            log.error("Failed to prepare Nix workspace", ex);
            throw new BadRequestException("Could not prepare Nix project: " + ex.getMessage());
        } finally {
            cleanupServices(serviceRuntimes);
            deleteNetwork(networkName);
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
            return new ProjectRunSpec(ProjectEnvironment.NIX_FLAKE, true, command == null ? "true" : command, detectServices(files));
        }
        if (hasPath(files, "pom.xml")) {
            return new ProjectRunSpec(ProjectEnvironment.JAVA_MAVEN, false, command == null ? "mvn test" : command, detectServices(files));
        }
        if (hasPath(files, "build.gradle") || hasPath(files, "build.gradle.kts")) {
            return new ProjectRunSpec(ProjectEnvironment.JAVA_GRADLE, false, command == null ? "gradle test" : command, detectServices(files));
        }
        if (hasPath(files, "package.json")) {
            return new ProjectRunSpec(ProjectEnvironment.NODE, false, command == null ? defaultNodeCommand(files) : command, detectServices(files));
        }
        if (hasPath(files, "requirements.txt") || hasPath(files, "pyproject.toml")) {
            return new ProjectRunSpec(ProjectEnvironment.PYTHON, false, command == null ? defaultPythonCommand(files) : command, detectServices(files));
        }
        if (hasPath(files, "cargo.toml")) {
            return new ProjectRunSpec(ProjectEnvironment.RUST, false, command == null ? "cargo test" : command, detectServices(files));
        }
        if (hasPath(files, "go.mod")) {
            return new ProjectRunSpec(ProjectEnvironment.GO, false, command == null ? "go test ./..." : command, detectServices(files));
        }
        if (hasPath(files, "cmakelists.txt")) {
            return new ProjectRunSpec(ProjectEnvironment.CMAKE, false, command == null ? "cmake -S . -B build && cmake --build build" : command, detectServices(files));
        }
        if (hasPath(files, "composer.json")) {
            return new ProjectRunSpec(ProjectEnvironment.PHP, false, command == null ? "composer install && composer test" : command, detectServices(files));
        }
        if (hasPath(files, "gemfile")) {
            return new ProjectRunSpec(ProjectEnvironment.RUBY, false, command == null ? "bundle install && ruby app.rb" : command, detectServices(files));
        }
        if (files.stream().anyMatch(file -> {
            String path = normalizeRunPathForCompare(file.path());
            return path != null && (path.endsWith(".csproj") || path.endsWith(".sln"));
        })) {
            return new ProjectRunSpec(ProjectEnvironment.DOTNET, false, command == null ? "dotnet run" : command, detectServices(files));
        }
        if (files.stream().anyMatch(file -> {
            String path = normalizeRunPathForCompare(file.path());
            return path != null && path.endsWith(".sql");
        })) {
            return new ProjectRunSpec(ProjectEnvironment.SQLITE, false, command == null ? defaultSqliteCommand(files) : command, detectServices(files));
        }
        throw new BadRequestException("No Nix-compatible project environment detected");
    }

    String generatedFlake(ProjectEnvironment environment) {
        String packages = switch (environment) {
            case JAVA_MAVEN -> "jdk21_headless maven";
            case JAVA_GRADLE -> "jdk21_headless gradle";
            case NODE -> "nodejs_20 python312 gnumake gcc pkg-config";
            case PYTHON -> "python312 python312Packages.pip python312Packages.pytest gcc pkg-config";
            case RUST -> "rustc cargo rustfmt clippy";
            case GO -> "go";
            case CMAKE -> "gcc cmake gnumake pkg-config";
            case PHP -> "php83 php83Packages.composer";
            case RUBY -> "ruby bundler";
            case DOTNET -> "dotnet-sdk_8";
            case SQLITE -> "sqlite";
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

    List<String> buildDockerCommand(String containerName, Path workspace, ProjectRunSpec spec) {
        return buildDockerCommand(containerName, workspace, spec, null, List.of());
    }

    List<String> buildDockerCommand(String containerName, Path workspace, ProjectRunSpec spec, String networkName, List<ServiceRuntime> services) {
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
        if (networkName != null) {
            cmd.add("--network");
            cmd.add(networkName);
        }
        cmd.add("-v");
        cmd.add(workspace.toAbsolutePath() + ":/workspace");
        cmd.add("-v");
        cmd.add(props.cacheVolume() + ":/nix");
        addDependencyCacheMounts(cmd, spec.environment());
        addServiceEnvironment(cmd, services);
        cmd.add("--env=CODELEON_PROJECT_COMMAND=" + spec.command());
        cmd.add("--workdir=/workspace");
        cmd.add(props.image());
        cmd.add("sh");
        cmd.add("-lc");
        cmd.add("nix --extra-experimental-features 'nix-command flakes' develop --command sh -lc \"$CODELEON_PROJECT_COMMAND\"");
        return cmd;
    }

    private void addDependencyCacheMounts(List<String> cmd, ProjectEnvironment environment) {
        if (environment == ProjectEnvironment.JAVA_MAVEN
                || environment == ProjectEnvironment.JAVA_GRADLE
                || environment == ProjectEnvironment.NIX_FLAKE) {
            cmd.add("-v");
            cmd.add(props.mavenCacheVolume() + ":/root/.m2");
        }
        if (environment == ProjectEnvironment.NODE || environment == ProjectEnvironment.NIX_FLAKE) {
            cmd.add("-v");
            cmd.add(props.npmCacheVolume() + ":/root/.npm");
            cmd.add("--env=NPM_CONFIG_CACHE=/root/.npm");
        }
        if (environment == ProjectEnvironment.PYTHON || environment == ProjectEnvironment.NIX_FLAKE) {
            cmd.add("-v");
            cmd.add(props.pipCacheVolume() + ":/root/.cache/pip");
            cmd.add("--env=PIP_CACHE_DIR=/root/.cache/pip");
        }
    }

    private void addServiceEnvironment(List<String> cmd, List<ServiceRuntime> services) {
        for (ServiceRuntime service : services) {
            switch (service.kind()) {
                case "postgres" -> {
                    cmd.add("--env=DATABASE_URL=postgres://codeleon:codeleon@" + service.containerName() + ":5432/codeleon");
                    cmd.add("--env=POSTGRES_HOST=" + service.containerName());
                }
                case "mysql" -> {
                    cmd.add("--env=MYSQL_HOST=" + service.containerName());
                    cmd.add("--env=MYSQL_USER=codeleon");
                    cmd.add("--env=MYSQL_PASSWORD=codeleon");
                    cmd.add("--env=MYSQL_DATABASE=codeleon");
                }
                case "mongodb" -> cmd.add("--env=MONGO_URL=mongodb://" + service.containerName() + ":27017");
                case "redis" -> cmd.add("--env=REDIS_URL=redis://" + service.containerName() + ":6379");
                default -> {
                }
            }
        }
    }

    private void createNetwork(String networkName) {
        runDockerControl(List.of("docker", "network", "create", networkName), "create runner network");
    }

    private void deleteNetwork(String networkName) {
        if (networkName == null) {
            return;
        }
        try {
            new ProcessBuilder("docker", "network", "rm", networkName).start().waitFor(5, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to remove Nix runner network {}", networkName, ex);
        }
    }

    private List<ServiceRuntime> startServices(String networkName, List<String> services) {
        List<ServiceRuntime> runtimes = new ArrayList<>();
        try {
            for (String service : services) {
                String containerName = "codeleon-nix-" + service + "-" + UUID.randomUUID();
                List<String> command = sidecarCommand(service, containerName, networkName);
                if (command.isEmpty()) {
                    continue;
                }
                runDockerControl(command, "start " + service + " sidecar");
                ServiceRuntime runtime = new ServiceRuntime(service, containerName);
                runtimes.add(runtime);
                waitForService(runtime);
            }
        } catch (RuntimeException ex) {
            cleanupServices(runtimes);
            throw ex;
        }
        return List.copyOf(runtimes);
    }

    private List<String> sidecarCommand(String service, String containerName, String networkName) {
        List<String> cmd = new ArrayList<>(List.of(
                "docker", "run", "-d", "--rm", "--name", containerName, "--network", networkName
        ));
        switch (service) {
            case "postgres" -> {
                cmd.addAll(List.of(
                        "-e", "POSTGRES_DB=codeleon",
                        "-e", "POSTGRES_USER=codeleon",
                        "-e", "POSTGRES_PASSWORD=codeleon",
                        "postgres:16-alpine"
                ));
            }
            case "mysql" -> {
                cmd.addAll(List.of(
                        "-e", "MYSQL_DATABASE=codeleon",
                        "-e", "MYSQL_USER=codeleon",
                        "-e", "MYSQL_PASSWORD=codeleon",
                        "-e", "MYSQL_ROOT_PASSWORD=codeleon",
                        "mysql:8"
                ));
            }
            case "mongodb" -> cmd.add("mongo:7");
            case "redis" -> cmd.add("redis:7-alpine");
            default -> {
                return List.of();
            }
        }
        return cmd;
    }

    private void waitForService(ServiceRuntime service) {
        List<String> check = switch (service.kind()) {
            case "postgres" -> List.of("docker", "exec", service.containerName(), "pg_isready", "-U", "codeleon", "-d", "codeleon");
            case "mysql" -> List.of("docker", "exec", service.containerName(), "mysqladmin", "ping", "-h", "127.0.0.1", "-ucodeleon", "-pcodeleon");
            case "mongodb" -> List.of("docker", "exec", service.containerName(), "mongosh", "--quiet", "--eval", "db.runCommand({ ping: 1 }).ok");
            case "redis" -> List.of("docker", "exec", service.containerName(), "redis-cli", "ping");
            default -> List.of();
        };
        if (check.isEmpty()) {
            return;
        }
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                Process process = new ProcessBuilder(check).start();
                if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    return;
                }
                Thread.sleep(1_000);
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        throw new BadRequestException("Timed out waiting for " + service.kind() + " service");
    }

    private void cleanupServices(List<ServiceRuntime> services) {
        for (ServiceRuntime service : services) {
            try {
                new ProcessBuilder("docker", "kill", service.containerName()).start().waitFor(5, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                log.warn("Failed to stop Nix runner service {}", service.containerName(), ex);
            }
        }
    }

    private void runDockerControl(List<String> command, String action) {
        try {
            Process process = new ProcessBuilder(command).start();
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
                String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new BadRequestException("Could not " + action + ": " + stderr);
            }
        } catch (IOException ex) {
            throw new BadRequestException("Docker unavailable while trying to " + action + ": " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BadRequestException("Interrupted while trying to " + action);
        }
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

    private static List<String> detectServices(List<RunRequest.RunFile> files) {
        List<String> services = new ArrayList<>();
        String packageJson = findText(files, "package.json");
        if (packageJson != null) {
            String lower = packageJson.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("\"pg\"") || lower.contains("postgres")) {
                services.add("postgres");
            }
            if (lower.contains("\"mysql2\"") || lower.contains("\"mysql\"")) {
                services.add("mysql");
            }
            if (lower.contains("\"mongodb\"")) {
                services.add("mongodb");
            }
            if (lower.contains("\"redis\"")) {
                services.add("redis");
            }
        }
        return List.copyOf(services);
    }

    private static String defaultSqliteCommand(List<RunRequest.RunFile> files) {
        if (hasPath(files, "queries.sql")) {
            return "sqlite3 :memory: < queries.sql";
        }
        String firstSql = files.stream()
                .map(file -> normalizeRunPathForCompare(file.path()))
                .filter(path -> path != null && path.endsWith(".sql"))
                .findFirst()
                .orElse("schema.sql");
        return "sqlite3 :memory: < " + firstSql;
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
            return withPythonVenv(dependencyInstall, "python -m pytest", true);
        }
        if (hasPath(files, "main.py")) {
            return dependencyInstall == null
                    ? "python main.py"
                    : withPythonVenv(dependencyInstall, "python main.py", false);
        }
        String compileCommand = "python -m py_compile $(find . -name '*.py' -type f)";
        return dependencyInstall == null
                ? compileCommand
                : withPythonVenv(dependencyInstall, compileCommand, false);
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

    private static String withPythonVenv(String installCommand, String runCommand, boolean installPytest) {
        List<String> commands = new ArrayList<>();
        commands.add("python -m venv .codeleon-venv");
        commands.add(". .codeleon-venv/bin/activate");
        if (installCommand != null) {
            commands.add(installCommand);
        }
        if (installPytest) {
            commands.add("python -m pip install pytest");
        }
        commands.add(runCommand);
        return String.join(" && ", commands);
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
        JAVA_GRADLE("Generated Java/Gradle"),
        NODE("Generated Node"),
        PYTHON("Generated Python"),
        RUST("Generated Rust/Cargo"),
        GO("Generated Go"),
        CMAKE("Generated C/C++ CMake"),
        PHP("Generated PHP/Composer"),
        RUBY("Generated Ruby/Bundler"),
        DOTNET("Generated .NET"),
        SQLITE("Generated SQLite");

        private final String label;

        ProjectEnvironment(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record ProjectRunSpec(ProjectEnvironment environment, boolean projectFlake, String command, List<String> services) {
    }

    record ServiceRuntime(String kind, String containerName) {
    }
}
