import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * ServerStats — Level 2.4: Synchronization Hardening
 *
 * Immutable point-in-time snapshot of server telemetry metrics.
 * Produced by SharedClientRegistry.getStats() and safe to pass between threads.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY IMMUTABLE?
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   A stats object may be created on one thread and read on another (e.g., a
 *   monitoring thread, a test assertion, a future admin command handler).
 *
 *   If the fields were mutable, the reading thread might see partially updated
 *   values — a classic memory visibility hazard. The JVM does not guarantee
 *   that writes on one thread are visible to other threads unless a happens-before
 *   relationship is established (via synchronized, volatile, or final).
 *
 *   The Java Memory Model (JMM) guarantees that all writes to FINAL fields made
 *   in the constructor are visible to ALL threads after the constructor returns.
 *   No synchronized, no volatile needed on the reading side.
 *
 *   Rule: value objects passed between threads should always be immutable.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * USAGE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   ServerStats stats = SharedClientRegistry.getInstance().getStats();
 *   Logger.logServer(stats.toString());
 *
 *   Or individual fields:
 *   Logger.logServer("Active clients: " + stats.activeClients);
 */
public class ServerStats
{
    // ─────────────────────────────────────────────────────────────────────────
    // FIELDS — all final (immutable after construction)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Total client connections accepted since server start.
     * Monotonic — only ever increases. Never reflects disconnections.
     * Useful for computing total server throughput over time.
     */
    public final long totalConnectionsAccepted;

    /**
     * Number of clients currently registered in the shared registry.
     * Snapshot value — may be stale by the time the caller reads it.
     * That is acceptable: telemetry snapshots are inherently approximate.
     */
    public final int activeClients;

    /**
     * Total number of broadcast events dispatched since server start.
     * One increment per BroadcastMessage() call — not per recipient.
     */
    public final long totalMessagesRelayed;

    /**
     * Approximate total bytes written to all client output streams since start.
     * Approximation based on message char-count × recipient count.
     * Useful for throughput trending and load profiling in Level 2.5.
     */
    public final long totalBytesSent;

    /**
     * ISO-formatted timestamp of when this snapshot was taken.
     * Allows log correlation: match a stats line to an exact moment in time.
     */
    public final String timestamp;

    /** Formatter for the snapshot timestamp. Thread-safe (DateTimeFormatter is immutable). */
    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");


    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constructs an immutable server stats snapshot.
     *
     * Called only from SharedClientRegistry.getStats().
     * All arguments come from atomic reads — guaranteed to be individually
     * consistent (though not collectively atomic across all four reads).
     * That level of precision is sufficient for observability purposes.
     *
     * @param totalConnectionsAccepted  Lifetime connection count from AtomicLong.get()
     * @param activeClients             Current live count from ConcurrentHashMap.size()
     * @param totalMessagesRelayed      Lifetime broadcast count from AtomicLong.get()
     * @param totalBytesSent            Lifetime byte approximation from AtomicLong.get()
     */
    public ServerStats(long totalConnectionsAccepted,
                       int  activeClients,
                       long totalMessagesRelayed,
                       long totalBytesSent)
    {
        this.totalConnectionsAccepted = totalConnectionsAccepted;
        this.activeClients            = activeClients;
        this.totalMessagesRelayed     = totalMessagesRelayed;
        this.totalBytesSent           = totalBytesSent;

        // Capture the exact wall-clock time this snapshot was taken.
        // LocalDateTime.now() is not timezone-aware — consistent within one JVM.
        this.timestamp = LocalDateTime.now().format(FORMATTER);
    }


    // ─────────────────────────────────────────────────────────────────────────
    // STRING REPRESENTATION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns a single-line human-readable summary of this stats snapshot.
     * Suitable for direct use in Logger.logServer() calls.
     *
     * Example output:
     *   [ServerStats @ 2026-05-22 22:55:01.234] Active: 3 | TotalConnected: 7
     *   | MessagesRelayed: 42 | BytesSent: 8610
     */
    @Override
    public String toString()
    {
        return String.format(
            "[ServerStats @ %s] Active: %d | TotalConnected: %d | MessagesRelayed: %d | BytesSent: %d",
            timestamp,
            activeClients,
            totalConnectionsAccepted,
            totalMessagesRelayed,
            totalBytesSent
        );
    }
}
