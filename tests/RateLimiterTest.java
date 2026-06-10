import security.RateLimiter;
import security.RateLimitPolicy;

/**
 * Tests for the token-bucket {@link security.RateLimiter}.
 *
 * <p>Validates that:
 * <ul>
 *   <li>Normal traffic within burst limit is allowed.</li>
 *   <li>Rapid-fire flood beyond burst is rejected.</li>
 *   <li>Session eviction clears state.</li>
 *   <li>Tokens refill over time.</li>
 * </ul>
 *
 * <p>Run directly (no external test framework required):
 * <pre>
 *   javac -sourcepath src tests/RateLimiterTest.java
 *   java -cp . RateLimiterTest
 * </pre>
 */
public class RateLimiterTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== RateLimiterTest ===\n");

        testNormalTrafficAllowed();
        testFloodIsRejected();
        testEvictionClearsViolationCount();
        testTokenRefillOverTime();
        testMultiSessionIsolation();

        System.out.println("\n--- Results ---");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        if (failed > 0) { System.exit(1); }
    }

    // -----------------------------------------------------------------------

    private static void testNormalTrafficAllowed() {
        RateLimiter limiter = new RateLimiter(new RateLimitPolicy(5, 2, 0));
        String sid = "session-normal";
        // First 5 requests within burst should be allowed
        int allowed = 0;
        for (int i = 0; i < 5; i++) {
            if (limiter.allowRequest(sid)) allowed++;
        }
        assertTrue("Normal traffic within burst limit allowed", allowed == 5);
    }

    private static void testFloodIsRejected() {
        // Very tight limiter: burst=3, refill=1/s
        RateLimiter limiter = new RateLimiter(new RateLimitPolicy(3, 1, 0));
        String sid = "session-flood";
        int rejected = 0;
        for (int i = 0; i < 20; i++) {
            if (!limiter.allowRequest(sid)) rejected++;
        }
        assertTrue("Flood beyond burst limit is rejected (at least 15 of 20 rejected)",
            rejected >= 15);
    }

    private static void testEvictionClearsViolationCount() {
        RateLimiter limiter = new RateLimiter(new RateLimitPolicy(2, 1, 0));
        String sid = "session-evict";
        // Cause violations
        for (int i = 0; i < 10; i++) { limiter.allowRequest(sid); }
        int before = limiter.getViolationCount(sid);
        limiter.evict(sid);
        int after = limiter.getViolationCount(sid);
        assertTrue("Violation count is positive before eviction", before > 0);
        assertTrue("Violation count is zero after eviction", after == 0);
    }

    private static void testTokenRefillOverTime() throws InterruptedException {
        // Burst=1, refill=5/s → after 300ms should have ~1.5 tokens
        RateLimiter limiter = new RateLimiter(new RateLimitPolicy(1, 5, 0));
        String sid = "session-refill";
        limiter.allowRequest(sid); // consume the single token
        boolean blockedImmediately = !limiter.allowRequest(sid);
        Thread.sleep(400); // wait for refill
        boolean allowedAfterWait = limiter.allowRequest(sid);
        assertTrue("Request blocked immediately after burst exhausted", blockedImmediately);
        assertTrue("Request allowed after token refill period", allowedAfterWait);
    }

    private static void testMultiSessionIsolation() {
        RateLimiter limiter = new RateLimiter(new RateLimitPolicy(2, 1, 0));
        String sid1 = "session-iso-1";
        String sid2 = "session-iso-2";
        // Exhaust sid1
        for (int i = 0; i < 10; i++) { limiter.allowRequest(sid1); }
        // sid2 should still be allowed up to burst
        boolean sid2Allowed = limiter.allowRequest(sid2);
        assertTrue("Session 2 is independent of Session 1's rate limit state", sid2Allowed);
    }

    // -----------------------------------------------------------------------

    private static void assertTrue(String name, boolean condition) {
        if (condition) {
            System.out.println("  [PASS] " + name);
            passed++;
        } else {
            System.out.println("  [FAIL] " + name);
            failed++;
        }
    }
}
