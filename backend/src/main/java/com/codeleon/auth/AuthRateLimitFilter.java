package com.codeleon.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP rate limit for the unauthenticated auth endpoints
 * ({@code POST /auth/login} and {@code POST /auth/register}) to blunt
 * brute-force / credential-stuffing / signup-abuse. In-memory token bucket —
 * fine for the single-instance deployment; a distributed limiter (Redis) would
 * be the move if the backend is ever scaled out.
 *
 * <p>Over the budget the filter returns {@code 429} with a {@code Retry-After}
 * header and does not reach the controller. The client IP is taken from
 * {@code getRemoteAddr()}, which reflects the real client because
 * {@code server.forward-headers-strategy=framework} is set (Caddy sits in
 * front).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    /** Requests allowed per IP per endpoint within the window. */
    private static final int CAPACITY = 10;
    /** Sliding refill window for a full bucket, in milliseconds (10 / minute). */
    private static final long WINDOW_MS = 60_000L;
    private static final long RETRY_AFTER_SECONDS = 60L;
    /** Soft ceiling on tracked buckets before an opportunistic sweep. */
    private static final int MAX_BUCKETS = 50_000;

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /** Off in the test profile so suites that register many users in one run
     *  are not throttled; on by default in dev/prod. */
    private final boolean enabled;

    public AuthRateLimitFilter(@Value("${codeleon.auth.rate-limit.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        return !(uri.endsWith("/auth/login") || uri.endsWith("/auth/register"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        // Key per (IP, endpoint) so a burst of registrations can't lock out
        // logins from the same address, and vice versa.
        String key = clientIp(request) + '|' + request.getRequestURI();
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(CAPACITY, WINDOW_MS));

        if (!bucket.tryConsume()) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After", String.valueOf(RETRY_AFTER_SECONDS));
            response.getWriter().write(
                    "{\"message\":\"Too many attempts. Please wait a moment and try again.\",\"status\":429}");
            return;
        }

        if (buckets.size() > MAX_BUCKETS) {
            // Drop buckets that have fully refilled (idle) — bounds memory
            // without a scheduled task.
            buckets.values().removeIf(TokenBucket::isFull);
        }

        filterChain.doFilter(request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        String addr = request.getRemoteAddr();
        return addr == null || addr.isBlank() ? "unknown" : addr;
    }

    /** Minimal thread-safe token bucket. */
    private static final class TokenBucket {
        private final int capacity;
        private final double refillPerMs;
        private double tokens;
        private long lastRefill;

        TokenBucket(int capacity, long windowMs) {
            this.capacity = capacity;
            this.refillPerMs = (double) capacity / windowMs;
            this.tokens = capacity;
            this.lastRefill = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized boolean isFull() {
            refill();
            return tokens >= capacity;
        }

        private void refill() {
            long now = System.currentTimeMillis();
            if (now > lastRefill) {
                tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerMs);
                lastRefill = now;
            }
        }
    }
}
