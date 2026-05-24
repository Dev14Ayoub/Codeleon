package com.codeleon.ai.metrics;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiMetricsServiceTest {

    @Test
    void freshSnapshotHasZeroedCounters() {
        AiMetricsSnapshot snap = new AiMetricsService().snapshot();
        assertThat(snap.totalTurns()).isZero();
        assertThat(snap.chatTurns()).isZero();
        assertThat(snap.agentTurns()).isZero();
        assertThat(snap.totalToolCalls()).isZero();
        assertThat(snap.toolCallsByName()).isEmpty();
        assertThat(snap.recentQueries()).isEmpty();
    }

    @Test
    void chatAndAgentTurnsCountSeparately() {
        AiMetricsService svc = new AiMetricsService();
        svc.recordTurn("chat", "hi", 120L, false);
        svc.recordTurn("chat", "again", 80L, false);
        svc.recordTurn("agent", "fix bug", 800L, false);

        AiMetricsSnapshot snap = svc.snapshot();
        assertThat(snap.chatTurns()).isEqualTo(2);
        assertThat(snap.agentTurns()).isEqualTo(1);
        assertThat(snap.totalTurns()).isEqualTo(3);
    }

    @Test
    void toolCallsTrackedByName() {
        AiMetricsService svc = new AiMetricsService();
        svc.recordToolCall("list_files");
        svc.recordToolCall("read_file");
        svc.recordToolCall("read_file");
        svc.recordToolCall("search_code");
        svc.recordToolCall("read_file");

        AiMetricsSnapshot snap = svc.snapshot();
        assertThat(snap.totalToolCalls()).isEqualTo(5);
        assertThat(snap.toolCallsByName()).containsEntry("read_file", 3L);
        assertThat(snap.toolCallsByName()).containsEntry("list_files", 1L);
        assertThat(snap.toolCallsByName()).containsEntry("search_code", 1L);
    }

    @Test
    void recentQueriesAreNewestFirstAndCapped() {
        AiMetricsService svc = new AiMetricsService();
        // Overshoot the 50-cap so we can verify the oldest entries fell off.
        for (int i = 0; i < 60; i++) {
            svc.recordTurn("chat", "q" + i, i, false);
        }
        AiMetricsSnapshot snap = svc.snapshot();
        assertThat(snap.recentQueries()).hasSize(50);
        // Newest first → the very last recorded query is on top.
        assertThat(snap.recentQueries().get(0).query()).isEqualTo("q59");
    }

    @Test
    void latencyPercentilesAreSensible() {
        AiMetricsService svc = new AiMetricsService();
        for (int i = 1; i <= 100; i++) {
            svc.recordTurn("chat", "q", i, false);
        }
        LatencyHistogram.Percentiles p = svc.snapshot().chatLatencyMs();
        assertThat(p.p50Ms()).isBetween(45L, 55L);
        assertThat(p.p95Ms()).isBetween(90L, 100L);
        assertThat(p.maxMs()).isEqualTo(100L);
    }

    @Test
    void failedTurnsAreCountedAndMarked() {
        AiMetricsService svc = new AiMetricsService();
        svc.recordTurn("agent", "broken", 50L, true);
        AiMetricsSnapshot snap = svc.snapshot();
        assertThat(snap.agentTurns()).isEqualTo(1);
        assertThat(snap.recentQueries().get(0).failed()).isTrue();
    }

    @Test
    void resetWipesEverything() {
        AiMetricsService svc = new AiMetricsService();
        svc.recordTurn("chat", "x", 50L, false);
        svc.recordToolCall("read_file");
        svc.recordAgentIterations(3);

        svc.reset();
        AiMetricsSnapshot snap = svc.snapshot();
        assertThat(snap.totalTurns()).isZero();
        assertThat(snap.totalToolCalls()).isZero();
        assertThat(snap.agentIterations()).isZero();
        assertThat(snap.toolCallsByName()).isEmpty();
        assertThat(snap.recentQueries()).isEmpty();
    }
}
