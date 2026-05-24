package fake.web;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Token-bucket rate limiter, in-memory. Each key (typically a user id
 * or an API key) gets {@code capacity} tokens that refill at
 * {@code refillRate} per second. {@link #tryAcquire} returns false
 * when the bucket is empty, which the web layer translates to a 429.
 */
public class RateLimiter {

    private final int capacity;
    private final double refillRate;
    private final Map<String, Bucket> buckets = new HashMap<>();

    public RateLimiter(int capacity, double refillRate) {
        this.capacity = capacity;
        this.refillRate = refillRate;
    }

    public synchronized boolean tryAcquire(String key) {
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(capacity, Instant.now()));
        Duration elapsed = Duration.between(bucket.lastRefill, Instant.now());
        double earned = elapsed.toMillis() * refillRate / 1000d;
        bucket.tokens = Math.min(capacity, bucket.tokens + earned);
        bucket.lastRefill = Instant.now();
        if (bucket.tokens >= 1.0) {
            bucket.tokens -= 1.0;
            return true;
        }
        return false;
    }

    private static final class Bucket {
        double tokens;
        Instant lastRefill;
        Bucket(int tokens, Instant lastRefill) {
            this.tokens = tokens;
            this.lastRefill = lastRefill;
        }
    }
}
