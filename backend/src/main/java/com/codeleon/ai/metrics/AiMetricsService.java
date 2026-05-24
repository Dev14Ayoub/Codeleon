package com.codeleon.ai.metrics;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-local counters + latency histograms for the AI pipeline.
 *
 * <p>State is kept in memory: a single-process backend doesn't need a
 * Prometheus exporter for a PFE demo, and the admin dashboard reads the
 * snapshot directly. If Codeleon ever scales out, this service is the
 * obvious seam to swap for a Micrometer registry.
 *
 * <p>Thread-safety: {@link AtomicLong} for counters, {@link ConcurrentHashMap}
 * for per-tool counts, {@link LatencyHistogram} for percentiles (already
 * synchronised), and a small synchronised ring buffer for recent queries.
 */
@Component
public class AiMetricsService {

    private static final int RECENT_QUERIES_CAP = 50;
    /** Cap the recorded query string so a giant copy-paste doesn't bloat the snapshot. */
    private static final int RECENT_QUERY_MAX_CHARS = 200;

    private final Instant startedAt = Instant.now();
    private final AtomicLong chatTurns = new AtomicLong();
    private final AtomicLong agentTurns = new AtomicLong();
    private final AtomicLong agentIterations = new AtomicLong();
    private final AtomicLong totalToolCalls = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> toolCallsByName = new ConcurrentHashMap<>();
    private final LatencyHistogram chatLatency = new LatencyHistogram();
    private final LatencyHistogram agentLatency = new LatencyHistogram();
    private final Deque<AiMetricsSnapshot.RecentQuery> recent = new ArrayDeque<>(RECENT_QUERIES_CAP);

    /**
     * Records one completed chat turn. {@code mode} is "chat" or "agent";
     * a turn that errored out should still be recorded with {@code failed=true}
     * so latency percentiles stay honest about real-world variance.
     */
    public void recordTurn(String mode, String query, long durationMs, boolean failed) {
        boolean isAgent = "agent".equalsIgnoreCase(mode);
        if (isAgent) {
            agentTurns.incrementAndGet();
            agentLatency.record(durationMs);
        } else {
            chatTurns.incrementAndGet();
            chatLatency.record(durationMs);
        }
        synchronized (recent) {
            if (recent.size() == RECENT_QUERIES_CAP) recent.removeFirst();
            recent.addLast(new AiMetricsSnapshot.RecentQuery(
                    Instant.now(),
                    isAgent ? "agent" : "chat",
                    cap(query, RECENT_QUERY_MAX_CHARS),
                    durationMs,
                    failed
            ));
        }
    }

    public void recordAgentIterations(int iterations) {
        if (iterations > 0) agentIterations.addAndGet(iterations);
    }

    public void recordToolCall(String name) {
        if (name == null || name.isBlank()) return;
        totalToolCalls.incrementAndGet();
        toolCallsByName.computeIfAbsent(name, k -> new AtomicLong()).incrementAndGet();
    }

    public AiMetricsSnapshot snapshot() {
        Map<String, Long> tools = new TreeMap<>();
        toolCallsByName.forEach((k, v) -> tools.put(k, v.get()));

        List<AiMetricsSnapshot.RecentQuery> recentCopy;
        synchronized (recent) {
            recentCopy = new ArrayList<>(recent);
        }
        // Newest first — nicer to read in the admin table.
        java.util.Collections.reverse(recentCopy);

        long chat = chatTurns.get();
        long agent = agentTurns.get();

        return new AiMetricsSnapshot(
                startedAt,
                chat + agent,
                chat,
                agent,
                agentIterations.get(),
                totalToolCalls.get(),
                tools,
                chatLatency.percentiles(),
                agentLatency.percentiles(),
                chatLatency.mean(),
                agentLatency.mean(),
                recentCopy
        );
    }

    /** Wipes all counters/latencies/queries. Bound to an admin "reset" action. */
    public void reset() {
        chatTurns.set(0);
        agentTurns.set(0);
        agentIterations.set(0);
        totalToolCalls.set(0);
        toolCallsByName.clear();
        chatLatency.reset();
        agentLatency.reset();
        synchronized (recent) {
            recent.clear();
        }
    }

    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
