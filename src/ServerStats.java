import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// -------------------------------------------------------------------------
// ServerStats — AegisCore Level 2.4: Synchronization Hardening
// -------------------------------------------------------------------------

/**
 * An immutable, point-in-time snapshot of server telemetry metrics for the
 * AegisCore multi-threaded server system.
 *
 * <p>Instances are produced by {@code SharedClientRegistry.getStats()} and
 * represent a consistent view of server state captured at a single instant.
 * Callers may freely pass, store, and read {@code ServerStats} objects across
 * threads without any additional synchronization.
 *
 * <p><b>Thread-safety / Java Memory Model guarantee:</b><br>
 * Every field is declared {@code public final}. The Java Memory Model (JMM)
 * guarantees that all writes to {@code final} fields performed inside a
 * constructor are flushed and visible to <em>all</em> threads once the
 * constructor returns. Consequently, no {@code synchronized} block, no
 * {@code volatile} keyword, and no explicit memory barrier is required on
 * the reading side — safe publication is achieved automatically.
 *
 * <p><b>Design pattern:</b> Immutable value object (record-like). Once
 * constructed, the object's state never changes, eliminating entire classes
 * of data races.
 *
 * <p><b>Usage example:</b>
 * <pre>{@code
 *   ServerStats stats = SharedClientRegistry.getInstance().getStats();
 *   Logger.logServer(stats.toString());
 * }</pre>
 */
public class ServerStats
{
    // -------------------------------------------------------------------------
    // Static fields
    // -------------------------------------------------------------------------

    /**
     * Formatter used to render the snapshot timestamp in log output.
     *
     * <p>{@link DateTimeFormatter} instances are immutable and inherently
     * thread-safe, so this constant requires no synchronization regardless of
     * how many threads call {@link #toString()} concurrently.
     */
    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // -------------------------------------------------------------------------
    // Immutable telemetry fields
    // -------------------------------------------------------------------------

    /**
     * Cumulative number of TCP connections accepted by the server since startup.
     *
     * <p>This is a monotonically increasing counter; it is never decremented
     * when a client disconnects. Declared {@code final} to guarantee JMM
     * safe-publication across threads.
     */
    public final long totalConnectionsAccepted;

    /**
     * Number of clients that were actively connected at the moment this
     * snapshot was taken.
     *
     * <p>Unlike {@link #totalConnectionsAccepted}, this value reflects the
     * instantaneous live-client count and can both rise and fall over time.
     * Declared {@code final} to guarantee JMM safe-publication across threads.
     */
    public final int activeClients;

    /**
     * Total number of messages relayed between clients since server startup.
     *
     * <p>Each relay operation — regardless of how many recipients receive the
     * message — increments this counter by one. Declared {@code final} to
     * guarantee JMM safe-publication across threads.
     */
    public final long totalMessagesRelayed;

    /**
     * Approximate total bytes sent to all clients since server startup.
     *
     * <p>Computed as {@code message.length * recipientCount} for each relay
     * operation. This is an estimate rather than a precise wire-byte count
     * because it does not account for protocol framing overhead. Declared
     * {@code final} to guarantee JMM safe-publication across threads.
     */
    public final long totalBytesSent;

    /**
     * Wall-clock timestamp (formatted as {@code yyyy-MM-dd HH:mm:ss.SSS})
     * captured at the moment this snapshot was constructed.
     *
     * <p>Intended for log correlation: comparing timestamps across successive
     * {@code ServerStats} snapshots allows operators to measure the rate of
     * change for any metric. Declared {@code final} to guarantee JMM
     * safe-publication across threads.
     */
    public final String timestamp;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs a new immutable {@code ServerStats} snapshot from the supplied
     * telemetry values.
     *
     * <p>The {@link #timestamp} field is set to the wall-clock time at the
     * moment this constructor executes. After the constructor returns, every
     * field is permanently fixed and visible to all threads per the JMM
     * {@code final}-field guarantee.
     *
     * @param totalConnectionsAccepted cumulative count of TCP connections
     *     accepted since server startup; must be {@code >= 0}
     * @param activeClients            number of clients connected at the
     *     instant of snapshot creation; must be {@code >= 0}
     * @param totalMessagesRelayed     cumulative count of messages relayed
     *     between clients since server startup; must be {@code >= 0}
     * @param totalBytesSent           approximate cumulative bytes dispatched
     *     to all clients since server startup (see {@link #totalBytesSent}
     *     for precision caveats); must be {@code >= 0}
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

        // Capture wall-clock time once, at construction, so the timestamp
        // is consistent with the metric values recorded above.
        this.timestamp = LocalDateTime.now().format(FORMATTER);
    }

    // -------------------------------------------------------------------------
    // Object overrides
    // -------------------------------------------------------------------------

    /**
     * Returns a human-readable, single-line summary of this snapshot suitable
     * for structured log output.
     *
     * <p>The format is:
     * <pre>
     * [ServerStats @ yyyy-MM-dd HH:mm:ss.SSS] Active: N | TotalConnected: N |
     *     MessagesRelayed: N | BytesSent: N
     * </pre>
     *
     * @return a formatted string containing all telemetry fields and the
     *     snapshot timestamp; never {@code null}
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
