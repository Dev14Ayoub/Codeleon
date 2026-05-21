package com.codeleon.runner;

import com.codeleon.common.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@EnableConfigurationProperties(CodeRunnerProperties.class)
public class DockerCodeRunnerService implements CodeRunnerService {

    private static final int MAX_PROJECT_FILES = 250;
    private static final int MAX_PROJECT_BYTES = 750_000;
    private static final Logger log = LoggerFactory.getLogger(DockerCodeRunnerService.class);
    private static final Pattern JAVA_PACKAGE_PATTERN = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;"
    );
    private static final Pattern JAVA_PUBLIC_TYPE_PATTERN = Pattern.compile(
            "\\bpublic\\s+(?:abstract\\s+|final\\s+|sealed\\s+|non-sealed\\s+)?(?:class|record|enum)\\s+([A-Za-z_$][\\w$]*)\\b"
    );
    private static final Pattern JAVA_TYPE_PATTERN = Pattern.compile(
            "\\b(?:class|record|enum)\\s+([A-Za-z_$][\\w$]*)\\b"
    );
    private static final Pattern JAVA_IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z_$][\\w$]*");

    private final CodeRunnerProperties props;

    public DockerCodeRunnerService(CodeRunnerProperties props) {
        this.props = props;
    }

    @Override
    public RunResult run(RunRequest request) {
        if (!props.enabled()) {
            throw new BadRequestException("Code execution is disabled on this server");
        }
        return switch (request.language()) {
            case PYTHON -> runPython(request);
            case JAVA -> runJava(request);
        };
    }

    private RunResult runPython(RunRequest request) {
        String containerName = "codeleon-runner-" + UUID.randomUUID();
        List<String> command = buildBaseCommand(containerName, props.pythonImage(), List.of(
                "PYTHONDONTWRITEBYTECODE=1",
                "CODELEON_CODE_BYTES=" + codeBytes(request)
        ));
        command.add("/bin/sh");
        command.add("-c");
        command.add("set -eu; mkdir -p /tmp/codeleon; cd /tmp/codeleon; "
                + "dd bs=1 count=\"$CODELEON_CODE_BYTES\" of=main.py 2>/dev/null; "
                + "python main.py");
        return execute(command, containerName, runnerInput(request), props.timeoutMs());
    }

    private RunResult runJava(RunRequest request) {
        JavaRunSpec spec = javaRunSpec(request);
        if (hasRootPom(request)) {
            return runMavenJava(request, spec);
        }

        String containerName = "codeleon-runner-" + UUID.randomUUID();
        List<String> command = buildBaseCommand(containerName, props.javaImage(), List.of(
                "CODELEON_CODE_BYTES=" + codeBytes(request),
                "CODELEON_SOURCE=" + spec.sourceFile(),
                "CODELEON_MAIN_CLASS=" + spec.mainClass()
        ));
        command.add("/bin/sh");
        command.add("-c");
        command.add("set -eu; mkdir -p /tmp/codeleon; cd /tmp/codeleon; "
                + "dd bs=1 count=\"$CODELEON_CODE_BYTES\" of=\"$CODELEON_SOURCE\" 2>/dev/null; "
                + "javac -d . \"$CODELEON_SOURCE\"; "
                + "java -cp . \"$CODELEON_MAIN_CLASS\"");
        return execute(command, containerName, runnerInput(request), props.javaTimeoutMs());
    }

    private RunResult runMavenJava(RunRequest request, JavaRunSpec spec) {
        Path workspace = null;
        try {
            workspace = Files.createTempDirectory("codeleon-maven-run-");
            materializeProject(request, workspace);

            String buildContainerName = "codeleon-runner-" + UUID.randomUUID();
            List<String> buildCommand = buildBaseCommand(
                    buildContainerName,
                    props.mavenImage(),
                    List.of(),
                    false,
                    props.mavenMemoryMb(),
                    List.of(workspace.toAbsolutePath() + ":/workspace", props.mavenCacheVolume() + ":/root/.m2")
            );
            buildCommand.add("/bin/sh");
            buildCommand.add("-c");
            buildCommand.add("set -eu; cd /workspace; "
                    + "mvn -q -DskipTests compile dependency:build-classpath "
                    + "-Dmdep.outputFile=target/codeleon-classpath.txt");

            RunResult build = execute(buildCommand, buildContainerName, "", props.mavenTimeoutMs());
            if (build.timedOut() || build.exitCode() != 0) {
                return build;
            }

            String runContainerName = "codeleon-runner-" + UUID.randomUUID();
            List<String> runCommand = buildBaseCommand(
                    runContainerName,
                    props.javaImage(),
                    List.of("CODELEON_MAIN_CLASS=" + spec.mainClass()),
                    true,
                    props.memoryMb(),
                    List.of(workspace.toAbsolutePath() + ":/workspace:ro", props.mavenCacheVolume() + ":/root/.m2:ro")
            );
            runCommand.add("/bin/sh");
            runCommand.add("-c");
            runCommand.add("set -eu; cd /workspace; CP=\"target/classes\"; "
                    + "if [ -s target/codeleon-classpath.txt ]; then CP=\"$CP:$(cat target/codeleon-classpath.txt)\"; fi; "
                    + "java -cp \"$CP\" \"$CODELEON_MAIN_CLASS\"");

            return execute(runCommand, runContainerName, request.stdin() == null ? "" : request.stdin(), props.javaTimeoutMs());
        } catch (IOException ex) {
            log.error("Failed to prepare Maven workspace", ex);
            throw new BadRequestException("Could not prepare Maven project: " + ex.getMessage());
        } finally {
            deleteWorkspace(workspace);
        }
    }

    private List<String> buildBaseCommand(String containerName, String image, List<String> environment) {
        return buildBaseCommand(containerName, image, environment, true, props.memoryMb(), List.of());
    }

    private List<String> buildBaseCommand(
            String containerName,
            String image,
            List<String> environment,
            boolean networkDisabled,
            int memoryMb,
            List<String> volumes
    ) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("-i");
        cmd.add("--name");
        cmd.add(containerName);
        if (networkDisabled) {
            cmd.add("--network=none");
        }
        cmd.add("--memory=" + memoryMb + "m");
        cmd.add("--memory-swap=" + memoryMb + "m");
        cmd.add("--cpus=" + props.cpus());
        cmd.add("--pids-limit=" + props.pidsLimit());
        cmd.add("--cap-drop=ALL");
        cmd.add("--security-opt=no-new-privileges");
        for (String volume : volumes) {
            cmd.add("-v");
            cmd.add(volume);
        }
        for (String env : environment) {
            cmd.add("--env=" + env);
        }
        cmd.add(image);
        return cmd;
    }

    private JavaRunSpec javaRunSpec(RunRequest request) {
        String packageName = matchFirstGroup(JAVA_PACKAGE_PATTERN, request.code());
        String className = matchFirstGroup(JAVA_PUBLIC_TYPE_PATTERN, request.code());
        if (className == null) {
            className = matchFirstGroup(JAVA_TYPE_PATTERN, request.code());
        }
        if (className == null) {
            className = classNameFromFilename(request.filename());
        }
        if (className == null) {
            className = "Main";
        }

        String mainClass = packageName == null ? className : packageName + "." + className;
        return new JavaRunSpec(className + ".java", mainClass);
    }

    private String matchFirstGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String classNameFromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }

        String normalized = filename.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (!basename.endsWith(".java")) {
            return null;
        }

        String withoutExtension = basename.substring(0, basename.length() - ".java".length());
        return JAVA_IDENTIFIER_PATTERN.matcher(withoutExtension).matches() ? withoutExtension : null;
    }

    private int codeBytes(RunRequest request) {
        return request.code().getBytes(StandardCharsets.UTF_8).length;
    }

    private String runnerInput(RunRequest request) {
        String stdin = request.stdin() == null ? "" : request.stdin();
        return request.code() + stdin;
    }

    private boolean hasRootPom(RunRequest request) {
        return request.files() != null
                && request.files().stream().anyMatch(file -> "pom.xml".equals(normalizeRunPath(file.path())));
    }

    private void materializeProject(RunRequest request, Path workspace) throws IOException {
        List<RunRequest.RunFile> files = request.files() == null ? List.of() : request.files();
        if (files.size() > MAX_PROJECT_FILES) {
            throw new BadRequestException("Maven projects are limited to " + MAX_PROJECT_FILES + " files for now");
        }

        int totalBytes = 0;
        for (RunRequest.RunFile file : files) {
            String text = file.text() == null ? "" : file.text();
            totalBytes += text.getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes > MAX_PROJECT_BYTES) {
                throw new BadRequestException("Maven project is too large to run in the sandbox");
            }
            writeWorkspaceFile(workspace, file.path(), text);
        }

        if (request.filename() != null && !request.filename().isBlank()) {
            writeWorkspaceFile(workspace, request.filename(), request.code());
        }

        if (!Files.exists(workspace.resolve("pom.xml"))) {
            throw new BadRequestException("Maven Java runs need a pom.xml at the project root");
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
        Files.writeString(target, text, StandardCharsets.UTF_8);
    }

    private String normalizeRunPath(String rawPath) {
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

    private void deleteWorkspace(Path workspace) {
        if (workspace == null) {
            return;
        }
        try (var paths = Files.walk(workspace)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.debug("Failed to delete runner workspace {}", path, ex);
                }
            });
        } catch (IOException ex) {
            log.debug("Failed to clean runner workspace {}", workspace, ex);
        }
    }

    private RunResult execute(List<String> command, String containerName, String input, int timeoutMs) {
        long start = System.currentTimeMillis();
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException ex) {
            log.error("Failed to spawn docker process", ex);
            throw new BadRequestException("Code runner unavailable: " + ex.getMessage());
        }

        try (OutputStream stdin = process.getOutputStream()) {
            if (input != null && !input.isEmpty()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ex) {
            log.warn("Failed to write code to runner stdin", ex);
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

    private void killContainer(String name) {
        try {
            new ProcessBuilder("docker", "kill", name).start().waitFor(3, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Failed to kill runner container {}", name, ex);
        }
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
                    if (total >= limit) {
                        break;
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

    private record JavaRunSpec(String sourceFile, String mainClass) {
    }
}
