package com.codeleon.runner.terminal;

import com.codeleon.common.exception.BadRequestException;
import com.codeleon.runner.CodeRunnerProperties;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Owns the lifecycle of interactive terminal containers: enforces the
 * concurrency cap, materializes the room's files, spawns a sandboxed
 * `docker run -i bash`, and reaps idle / over-long / dead sessions.
 *
 * <p>The container reuses the same hardening as the batch code runner
 * (--network=none, memory/cpu/pids caps, --cap-drop=ALL,
 * --security-opt=no-new-privileges) so a shell is no more privileged than a
 * one-shot run.
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(TerminalProperties.class)
public class TerminalSessionService {

    private static final Logger log = LoggerFactory.getLogger(TerminalSessionService.class);

    private final TerminalProperties props;
    private final WorkspaceMaterializer materializer;
    private final CodeRunnerProperties runnerProps;

    private final ConcurrentHashMap<String, TerminalSession> sessions = new ConcurrentHashMap<>();
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
     * Materializes the given files and starts a sandboxed bash process.
     * The returned session's process exposes the live stdin/stdout streams
     * the WebSocket handler pumps. Throws {@link BadRequestException} when the
     * feature is disabled or the concurrency cap is reached.
     */
    public TerminalSession create(String id, List<WorkspaceMaterializer.FileEntry> files) {
        if (!props.enabled()) {
            throw new BadRequestException("Interactive terminal is disabled on this server");
        }
        if (!slots().tryAcquire()) {
            throw new BadRequestException("Too many terminals are open right now. Try again in a moment.");
        }

        Path workspace = null;
        try {
            // Same-path rule as the Maven/Nix runners: create the workspace
            // under workspaceBaseDir so the path resolves identically on both
            // sides of the docker.sock bind mount in production.
            Path baseDir = Path.of(runnerProps.workspaceBaseDir());
            Files.createDirectories(baseDir);
            workspace = Files.createTempDirectory(baseDir, "codeleon-term-");
            materializer.materialize(workspace, files);

            String containerName = "codeleon-term-" + UUID.randomUUID();
            ProcessBuilder pb = new ProcessBuilder(buildCommand(containerName, workspace));
            // Merge stderr into stdout so the prompt + warnings interleave in
            // one stream, exactly like a real terminal.
            pb.redirectErrorStream(true);
            Process process = pb.start();

            TerminalSession session = new TerminalSession(id, process, containerName, workspace);
            sessions.put(id, session);
            log.info("Terminal session {} started (container {})", id, containerName);
            return session;
        } catch (IOException ex) {
            slots().release();
            deleteWorkspace(workspace);
            log.error("Failed to start terminal session {}", id, ex);
            throw new BadRequestException("Terminal unavailable: " + ex.getMessage());
        } catch (RuntimeException ex) {
            slots().release();
            deleteWorkspace(workspace);
            throw ex;
        }
    }

    private List<String> buildCommand(String containerName, Path workspace) {
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
        // Run as the user that owns the workspace files (the backend's own
        // uid) so the sandbox can read/write them even though CAP_DAC_OVERRIDE
        // is dropped — otherwise root inside the container cannot traverse a
        // 0700 dir owned by another uid (EACCES). This mirrors Replit's model,
        // where everything runs as the container's "runner" user that owns the
        // filesystem. On non-POSIX dev hosts (Windows) the owner uid is not
        // available, so we fall back to the image's default user.
        String userFlag = workspaceUserFlag(workspace);
        if (userFlag != null) {
            cmd.add("--user");
            cmd.add(userFlag);
        }
        cmd.add("-v");
        cmd.add(workspace.toAbsolutePath() + ":/workspace");
        cmd.add("--workdir=/workspace");
        // Unbuffered Python so input() prompts and print() output appear
        // immediately when stdout is a pipe rather than a TTY. HOME points at
        // the (writable) workspace because the run-as uid has no /etc/passwd
        // entry in the image, so tools that probe ~ still have a home.
        cmd.add("--env=PYTHONUNBUFFERED=1");
        cmd.add("--env=TERM=xterm-256color");
        cmd.add("--env=HOME=/workspace");
        cmd.add("--env=PS1=codeleon:\\w$ ");
        cmd.add(props.image());
        // --norc keeps the prompt clean; -i forces interactive mode (prompt +
        // line-by-line reads) even though stdin is a pipe, not a TTY.
        cmd.add("bash");
        cmd.add("--norc");
        cmd.add("-i");
        return cmd;
    }

    /**
     * Returns "uid:gid" of the workspace owner for {@code docker run --user},
     * or null when the POSIX owner can't be read (e.g. Windows dev hosts),
     * in which case the container uses the image's default user.
     */
    private String workspaceUserFlag(Path workspace) {
        try {
            Object uid = Files.getAttribute(workspace, "unix:uid");
            Object gid = Files.getAttribute(workspace, "unix:gid");
            if (uid instanceof Integer u && gid instanceof Integer g) {
                return u + ":" + g;
            }
        } catch (IOException | UnsupportedOperationException ex) {
            log.debug("Workspace owner uid unavailable; terminal runs as image default user", ex);
        }
        return null;
    }

    public void touch(String id) {
        TerminalSession session = sessions.get(id);
        if (session != null) {
            session.touch();
        }
    }

    /**
     * Rewrites the room's files into the live workspace so a subsequent command
     * sees the latest editor state. The workspace is a bind mount, so the
     * container picks up the changes immediately — no restart needed.
     */
    public void resync(String id, List<WorkspaceMaterializer.FileEntry> files) {
        TerminalSession session = sessions.get(id);
        if (session == null) {
            return;
        }
        try {
            materializer.materialize(session.workspace(), files);
            session.touch();
        } catch (IOException | RuntimeException ex) {
            log.warn("Failed to resync terminal workspace {}", id, ex);
        }
    }

    /** Best-effort delivery of a signal (currently only SIGINT) to the shell. */
    public void sendSignal(String id, String signal) {
        TerminalSession session = sessions.get(id);
        if (session == null) {
            return;
        }
        boolean isInterrupt = signal != null
                && (signal.equalsIgnoreCase("SIGINT") || signal.equalsIgnoreCase("INT"));
        if (!isInterrupt) {
            return;
        }
        try {
            new ProcessBuilder("docker", "kill", "--signal=INT", session.containerName())
                    .start()
                    .waitFor(3, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Failed to signal terminal container {}", session.containerName(), ex);
        }
    }

    /**
     * Tears a session down: kills the container, destroys the process, deletes
     * the workspace and frees the concurrency slot. Idempotent — the atomic
     * {@code remove} guarantees only the first caller does the work, so the
     * WebSocket close handler and the output pump can both call it safely.
     */
    public void terminate(String id) {
        TerminalSession session = sessions.remove(id);
        if (session == null) {
            return;
        }
        killContainer(session.containerName());
        try {
            session.process().destroyForcibly();
        } catch (RuntimeException ignored) {
            // process already gone
        }
        deleteWorkspace(session.workspace());
        slots().release();
        log.info("Terminal session {} terminated", id);
    }

    public int maxOutputBytes() {
        return props.maxOutputBytes();
    }

    /** Kills idle, over-long, or already-dead sessions on a fixed cadence. */
    @Scheduled(fixedDelay = 15_000)
    public void reapExpired() {
        long now = System.currentTimeMillis();
        List<String> expired = new ArrayList<>();
        for (TerminalSession session : sessions.values()) {
            boolean idle = now - session.lastActivityAt() > props.idleTimeoutMs();
            boolean tooLong = now - session.startedAt() > props.maxSessionMs();
            boolean dead = !session.process().isAlive();
            if (idle || tooLong || dead) {
                expired.add(session.id());
            }
        }
        for (String id : expired) {
            log.info("Reaping terminal session {}", id);
            terminate(id);
        }
    }

    private void killContainer(String name) {
        try {
            new ProcessBuilder("docker", "kill", name).start().waitFor(3, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.debug("Failed to kill terminal container {}", name, ex);
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
                    log.debug("Failed to delete terminal workspace entry {}", path, ex);
                }
            });
        } catch (IOException ex) {
            log.debug("Failed to clean terminal workspace {}", workspace, ex);
        }
    }
}
