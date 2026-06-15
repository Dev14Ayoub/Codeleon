package com.codeleon.runner;

import com.codeleon.common.exception.BadRequestException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;
import java.util.concurrent.Semaphore;

/**
 * Caps how many sandbox runs (single-file, Maven, and Nix project) execute
 * concurrently across the whole backend. Each run spawns a {@code docker run}
 * sibling that holds memory/CPU; without a global ceiling an authenticated
 * member could fire unbounded runs and exhaust the host. Shared by every
 * runner so the limit is a true host-wide budget, not a per-service one.
 *
 * <p>Acquisition is non-blocking: when every slot is taken the caller gets a
 * clear "busy" error rather than piling up Tomcat request threads (each of
 * which would otherwise sit blocked for the duration of a sibling run).
 */
@Component
@EnableConfigurationProperties(CodeRunnerProperties.class)
public class RunnerConcurrencyGate {

    private final Semaphore slots;

    public RunnerConcurrencyGate(CodeRunnerProperties props) {
        this.slots = new Semaphore(Math.max(1, props.maxConcurrentRuns()), true);
    }

    /** Runs {@code task} while holding a slot, or fails fast if none is free. */
    public <T> T call(Supplier<T> task) {
        if (!slots.tryAcquire()) {
            throw new BadRequestException(
                    "The code runner is busy — too many runs in progress. Try again in a moment.");
        }
        try {
            return task.get();
        } finally {
            slots.release();
        }
    }
}
