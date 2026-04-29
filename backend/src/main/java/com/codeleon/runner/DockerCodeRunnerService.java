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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
@EnableConfigurationProperties(CodeRunnerProperties.class)
public class DockerCodeRunnerService implements CodeRunnerService {

    private static final Logger log = LoggerFactory.getLogger(DockerCodeRunnerService.class);

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
        };
    }

    private RunResult runPython(RunRequest request) {
        String containerName = "codeleon-runner-" + UUID.randomUUID();
        List<String> command = buildBaseCommand(containerName, props.pythonImage());
        command.add("python");
        command.add("-");
        return execute(command, containerName, request);
    }

    private List<String> buildBaseCommand(String containerName, String image) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("-i");
        cmd.add("--name");
        cmd.add(containerName);
        cmd.add("--network=none");
        cmd.add("--memory=" + props.memoryMb() + "m");
        cmd.add("--memory-swap=" + props.memoryMb() + "m");
        cmd.add("--cpus=" + props.cpus());
        cmd.add("--pids-limit=" + props.pidsLimit());
        cmd.add("--cap-drop=ALL");
        cmd.add("--security-opt=no-new-privileges");
        cmd.add("--env=PYTHONDONTWRITEBYTECODE=1");
        cmd.add(image);
        return cmd;
    }

    private RunResult execute(List<String> command, String containerName, RunRequest request) {
        long start = System.currentTimeMillis();
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException ex) {
            log.error("Failed to spawn docker process", ex);
            throw new BadRequestException("Code runner unavailable: " + ex.getMessage());
        }

        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(request.code().getBytes(StandardCharsets.UTF_8));
            if (request.stdin() != null && !request.stdin().isEmpty()) {
                stdin.write("\n".getBytes(StandardCharsets.UTF_8));
                stdin.write(request.stdin().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ex) {
            log.warn("Failed to write code to runner stdin", ex);
        }

        CompletableFuture<String> stdoutFuture = readAsync(process.getInputStream());
        CompletableFuture<String> stderrFuture = readAsync(process.getErrorStream());

        boolean finished;
        try {
            finished = process.waitFor(props.timeoutMs(), TimeUnit.MILLISECONDS);
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
}
