package com.codeleon.runner.preview;

import com.codeleon.common.exception.BadRequestException;
import com.codeleon.runner.CodeRunnerProperties;
import com.codeleon.runner.terminal.WorkspaceMaterializer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Owns the lifecycle of live web-preview containers. Unlike the batch runners
 * and the terminal, a preview is long-lived ({@code docker run -d}) and has
 * network access, so it runs on a dedicated isolated network and is capped to a
 * single concurrent instance on the shared VM.
 *
 * <p>Security model: the preview container joins ONLY the {@code preview}
 * network, never the {@code internal} one where Postgres/Redis/Qdrant/Ollama
 * live. It keeps the same hardening as the other sandboxes (cap-drop=ALL,
 * no-new-privileges, memory/cpu/pids caps) and runs as the workspace owner uid.
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(PreviewProperties.class)
public class PreviewService {

    private static final Logger log = LoggerFactory.getLogger(PreviewService.class);
    private static final int MAX_COMMAND_LENGTH = 2_000;

    private final PreviewProperties props;
    private final WorkspaceMaterializer materializer;
    private final CodeRunnerProperties runnerProps;

    private final ConcurrentHashMap<UUID, PreviewSession> sessions = new ConcurrentHashMap<>();
    private volatile Semaphore slots;

    private Semaphore slots() {
        Semaphore local = slots;
        if (local == null) {
            synchronized (this) {
                local = slots;
                if (local == null) {
                    local = new Semaphore(props.maxConcurrentSessions());
                    slots = local;
                }
            }
        }
        return local;
    }

    /**
     * Materializes the room files and starts a detached dev-server container
     * running {@code command} (expected to bind 0.0.0.0:{port}). Restart
     * semantics: an existing preview for the room is stopped first.
     */
    public PreviewSession start(UUID roomId, String command, List<WorkspaceMaterializer.FileEntry> files) {
        if (!props.enabled()) {
            throw new BadRequestException("Live preview is disabled on this server");
        }
        if (command == null || command.isBlank()) {
            throw new BadRequestException("A preview command is required (e.g. npm run dev)");
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            throw new BadRequestException("Preview command is too long");
        }

        stop(roomId); // restart semantics + frees the slot if we held it
        if (!slots().tryAcquire()) {
            throw new BadRequestException("A preview is already running. Stop it before starting another.");
        }

        Path workspace = null;
        try {
            Path baseDir = Path.of(runnerProps.workspaceBaseDir());
            Files.createDirectories(baseDir);
            workspace = Files.createTempDirectory(baseDir, "codeleon-preview-");
            materializer.materialize(workspace, files);

            String containerName = "codeleon-preview-" + roomId;
            ProcessBuilder pb = new ProcessBuilder(buildCommand(containerName, workspace, command));
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // `docker run -d` prints the container id and exits immediately; the
            // dev server keeps running in the background.
            String output = readAll(process);
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                killContainer(containerName);
                throw new BadRequestException("Failed to start the preview container: " + output.strip());
            }

            PreviewSession session = new PreviewSession(roomId, containerName, props.port(), workspace, command);
            sessions.put(roomId, session);
            log.info("Preview {} started (container {}, command: {})", roomId, containerName, command);
            return session;
        } catch (IOException ex) {
            slots().release();
            deleteWorkspace(workspace);
            log.error("Failed to start preview {}", roomId, ex);
            throw new BadRequestException("Preview unavailable: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            slots().release();
            deleteWorkspace(workspace);
            throw new BadRequestException("Preview start interrupted");
        } catch (RuntimeException ex) {
            slots().release();
            deleteWorkspace(workspace);
            throw ex;
        }
    }

    public Optional<PreviewSession> get(UUID roomId) {
        return Optional.ofNullable(sessions.get(roomId));
    }

    public void touch(UUID roomId) {
        PreviewSession session = sessions.get(roomId);
        if (session != null) {
            session.touch();
        }
    }

    /** Stops and cleans up the room's preview, if any. Idempotent. */
    public void stop(UUID roomId) {
        PreviewSession session = sessions.remove(roomId);
        if (session == null) {
            return;
        }
        killContainer(session.containerName());
        deleteWorkspace(session.workspace());
        slots().release();
        log.info("Preview {} stopped", roomId);
    }

    private List<String> buildCommand(String containerName, Path workspace, String command) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("-d");
        cmd.add("--name");
        cmd.add(containerName);
        // Isolated network — NOT the data-tier network. This is the key control.
        cmd.add("--network");
        cmd.add(props.network());
        cmd.add("--memory=" + props.memoryMb() + "m");
        cmd.add("--memory-swap=" + props.memoryMb() + "m");
        cmd.add("--cpus=" + props.cpus());
        cmd.add("--pids-limit=" + props.pidsLimit());
        cmd.add("--cap-drop=ALL");
        cmd.add("--security-opt=no-new-privileges");
        String userFlag = workspaceUserFlag(workspace);
        if (userFlag != null) {
            cmd.add("--user");
            cmd.add(userFlag);
        }
        cmd.add("-v");
        cmd.add(workspace.toAbsolutePath() + ":/workspace");
        cmd.add("--workdir=/workspace");
        // The dev server must bind 0.0.0.0:$PORT to be reachable from the backend
        // (a different container). $HOME points at the writable workspace.
        cmd.add("--env=PORT=" + props.port());
        cmd.add("--env=HOST=0.0.0.0");
        cmd.add("--env=HOME=/workspace");
        cmd.add("--env=PYTHONUNBUFFERED=1");
        cmd.add(props.image());
        cmd.add("sh");
        cmd.add("-lc");
        cmd.add(command);
        return cmd;
    }

    /**
     * "uid:gid" of the workspace owner for {@code docker run --user}, so the
     * container can read/write its files with CAP_DAC_OVERRIDE dropped. Null on
     * non-POSIX dev hosts (Windows), where the image default user is used.
     */
    private String workspaceUserFlag(Path workspace) {
        try {
            Object uid = Files.getAttribute(workspace, "unix:uid");
            Object gid = Files.getAttribute(workspace, "unix:gid");
            if (uid instanceof Integer u && gid instanceof Integer g) {
                return u + ":" + g;
            }
        } catch (IOException | UnsupportedOperationException ex) {
            log.debug("Workspace owner uid unavailable; preview runs as image default user", ex);
        }
        return null;
    }

    /** Reaps idle, over-long, or dead previews on a fixed cadence. */
    @Scheduled(fixedDelay = 15_000)
    public void reapExpired() {
        long now = System.currentTimeMillis();
        List<UUID> expired = new ArrayList<>();
        for (PreviewSession session : sessions.values()) {
            boolean idle = now - session.lastActivityAt() > props.idleTimeoutMs();
            boolean tooLong = now - session.startedAt() > props.maxSessionMs();
            boolean dead = !isContainerRunning(session.containerName());
            if (idle || tooLong || dead) {
                expired.add(session.roomId());
            }
        }
        for (UUID roomId : expired) {
            log.info("Reaping preview {}", roomId);
            stop(roomId);
        }
    }

    private boolean isContainerRunning(String name) {
        try {
            Process p = new ProcessBuilder("docker", "inspect", "-f", "{{.State.Running}}", name).start();
            String out = readAll(p);
            p.waitFor(3, TimeUnit.SECONDS);
            return out.strip().equals("true");
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private void killContainer(String name) {
        try {
            new ProcessBuilder("docker", "kill", name).start().waitFor(3, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Failed to kill preview container {}", name, ex);
        }
    }

    private String readAll(Process process) throws IOException {
        try (var in = process.getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
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
                    log.debug("Failed to delete preview workspace entry {}", path, ex);
                }
            });
        } catch (IOException ex) {
            log.debug("Failed to clean preview workspace {}", workspace, ex);
        }
    }
}
