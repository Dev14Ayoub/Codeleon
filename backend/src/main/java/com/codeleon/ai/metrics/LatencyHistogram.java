package com.codeleon.ai.metrics;

import java.util.Arrays;

/**
 * Fixed-capacity ring buffer of latency samples (milliseconds), with
 * O(n log n) percentile computation on demand. Picked over a streaming
 * estimator (t-digest, HDRHistogram) because the snapshot is read by a
 * human a few times per day at most — exact-on-window beats approximate-
 * streaming when n is bounded to a few thousand samples and the read
 * path is rare.
 *
 * <p>Thread-safety: append is synchronised on the histogram itself;
 * snapshot copies the live buffer under the same lock so concurrent
 * writers cannot mutate the snapshot mid-sort.
 */
public final class LatencyHistogram {

    /** Default cap — about 8 KB of doubles per histogram. */
    private static final int DEFAULT_CAPACITY = 1024;

    private final int capacity;
    private final long[] samples;
    private int next;
    private int size;
    private long sum;

    public LatencyHistogram() {
        this(DEFAULT_CAPACITY);
    }

    public LatencyHistogram(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.samples = new long[capacity];
    }

    public synchronized void record(long latencyMs) {
        if (latencyMs < 0) return;
        sum -= samples[next];
        samples[next] = latencyMs;
        sum += latencyMs;
        next = (next + 1) % capacity;
        if (size < capacity) size++;
    }

    public synchronized int count() {
        return size;
    }

    public synchronized double mean() {
        return size == 0 ? 0.0d : (double) sum / size;
    }

    /**
     * Returns p50, p95, and max in milliseconds. Computed by copy-sorting
     * the active window — cheap at ~1k samples, called maybe twice an
     * hour from the admin page.
     */
    public synchronized Percentiles percentiles() {
        if (size == 0) return new Percentiles(0L, 0L, 0L);
        long[] copy = new long[size];
        System.arraycopy(samples, 0, copy, 0, size);
        Arrays.sort(copy);
        // Nearest-rank method: position = ceil(p*n), 1-indexed → 0-indexed.
        // Standard percentile definition (NIST). Floor(p*n) would put p50
        // on the larger of two samples when size=2 — visibly wrong on a
        // small window.
        long p50 = copy[rank(0.50d, size)];
        long p95 = copy[rank(0.95d, size)];
        long max = copy[size - 1];
        return new Percentiles(p50, p95, max);
    }

    private static int rank(double percentile, int size) {
        int pos = (int) Math.ceil(percentile * size) - 1;
        if (pos < 0) return 0;
        if (pos >= size) return size - 1;
        return pos;
    }

    public synchronized void reset() {
        Arrays.fill(samples, 0L);
        next = 0;
        size = 0;
        sum = 0L;
    }

    public record Percentiles(long p50Ms, long p95Ms, long maxMs) {}
}
