package com.codeleon.ai.metrics;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Point-in-time view of the AI pipeline's runtime state. Returned by
 * {@code GET /admin/ai-metrics}; populated by
 * {@link AiMetricsService#snapshot()}.
 */
public record AiMetricsSnapshot(
        Instant since,
        long totalTurns,
        long chatTurns,
        long agentTurns,
        long agentIterations,
        long totalToolCalls,
        Map<String, Long> toolCallsByName,
        LatencyHistogram.Percentiles chatLatencyMs,
        LatencyHistogram.Percentiles agentLatencyMs,
        double meanChatLatencyMs,
        double meanAgentLatencyMs,
        List<RecentQuery> recentQueries
) {
    public record RecentQuery(Instant at, String mode, String query, long durationMs, boolean failed) {}
}
