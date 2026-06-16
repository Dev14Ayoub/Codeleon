package com.codeleon.ai;

/**
 * Outcome of an indexing call. {@code failedFiles} is only meaningful for the
 * bulk {@code /index/all} endpoint, where individual files are isolated so one
 * failure does not abort the whole batch; single-file indexing always reports 0.
 */
public record IndexResult(int chunks, long durationMs, int failedFiles) {
    /** Convenience for single-file indexing (no per-file failure tracking). */
    public IndexResult(int chunks, long durationMs) {
        this(chunks, durationMs, 0);
    }
}
