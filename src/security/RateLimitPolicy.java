package security;

/**
 * Immutable configuration for the token-bucket rate limiter.
 *
 * @param maxBurst            maximum tokens a bucket can hold (burst capacity)
 * @param refillRatePerSecond tokens added per second (steady-state rate)
 * @param cooldownMillis      how long a violating session is locked out after exceeding the limit
 */
public record RateLimitPolicy(int maxBurst, int refillRatePerSecond, long cooldownMillis) {

    /** Default policy: 10-token burst, 5 tokens/sec refill, 2-second cooldown on violation. */
    public static RateLimitPolicy defaultPolicy() {
        return new RateLimitPolicy(10, 5, 2_000);
    }

    /** Strict policy for suspected abusers: 3-token burst, 1 token/sec, 5-second cooldown. */
    public static RateLimitPolicy strictPolicy() {
        return new RateLimitPolicy(3, 1, 5_000);
    }
}
