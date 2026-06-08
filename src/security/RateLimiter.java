package security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-session token-bucket rate limiter for AegisCore command processing.
 *
 * <p>Each client session gets its own {@link TokenBucket}. Tokens are refilled
 * continuously based on elapsed wall-clock time. When a client exhausts its
 * bucket, subsequent requests are rejected until enough tokens have refilled.
 *
 * <p>Thread-safe: each bucket is synchronized on its own monitor.
 */
public final class RateLimiter {

    private static final RateLimiter INSTANCE = new RateLimiter(RateLimitPolicy.defaultPolicy());

    private final RateLimitPolicy policy;
    private final ConcurrentHashMap<String, TokenBucket>  buckets    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> violations = new ConcurrentHashMap<>();

    /** Creates a rate limiter with the given policy. */
    public RateLimiter(RateLimitPolicy policy) {
        this.policy = policy;
    }

    /** Returns the default server-wide singleton rate limiter. */
    public static RateLimiter getInstance() {
        return INSTANCE;
    }

    /**
     * Returns {@code true} if the session is allowed to proceed; {@code false} if rate-limited.
     *
     * @param sessionId unique client session identifier
     * @return whether this request should be allowed
     */
    public boolean allowRequest(String sessionId) {
        TokenBucket bucket = buckets.computeIfAbsent(sessionId,
                id -> new TokenBucket(policy.maxBurst()));
        boolean allowed = bucket.tryConsume(policy.refillRatePerSecond(), policy.maxBurst());
        if (!allowed) {
            violations.computeIfAbsent(sessionId, id -> new AtomicInteger(0)).incrementAndGet();
        }
        return allowed;
    }

    /**
     * Removes all tracking state for a session. Call on client disconnect.
     *
     * @param sessionId session to evict
     */
    public void evict(String sessionId) {
        buckets.remove(sessionId);
        violations.remove(sessionId);
    }

    /**
     * Returns how many times this session has been blocked by the rate limiter.
     *
     * @param sessionId session to query
     * @return violation count, or 0 if never violated
     */
    public int getViolationCount(String sessionId) {
        AtomicInteger v = violations.get(sessionId);
        return v == null ? 0 : v.get();
    }

    // -----------------------------------------------------------------------
    // Inner class: token bucket
    // -----------------------------------------------------------------------

    /**
     * A single token bucket for one client session.
     * All access must be synchronized on {@code this}.
     */
    private static final class TokenBucket {
        private double tokens;
        private long   lastRefillNanos;

        TokenBucket(int initialTokens) {
            this.tokens = initialTokens;
            this.lastRefillNanos = System.nanoTime();
        }

        /**
         * Attempts to consume one token. Refills first based on elapsed time.
         *
         * @param refillRatePerSecond tokens added per second
         * @param maxBurst            bucket capacity cap
         * @return true if a token was consumed; false if bucket is empty
         */
        synchronized boolean tryConsume(int refillRatePerSecond, int maxBurst) {
            long nowNanos     = System.nanoTime();
            double elapsedSec = (nowNanos - lastRefillNanos) / 1_000_000_000.0;
            tokens = Math.min(maxBurst, tokens + elapsedSec * refillRatePerSecond);
            lastRefillNanos = nowNanos;

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
