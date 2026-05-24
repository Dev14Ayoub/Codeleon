package com.codeleon.ai.agent;

/**
 * Outcome of one chat turn run through the agent loop. {@code answer} is
 * the final assistant message the user sees; the counters are surfaced to
 * the UI in the {@code done} SSE event so operators can see how many
 * steps a turn took.
 */
public record AgentLoopResult(
        String answer,
        int toolCalls,
        int iterations,
        long durationMs
) {}
