import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Immutable, point-in-time snapshot of AegisCore server telemetry.
 *
 * <p>Instances are produced by {@link SharedClientRegistry#getStats()} and represent a
 * consistent view of server state captured at a single instant. Because every field is
 * {@code public final}, the Java Memory Model guarantees safe publication to all threads
 * once the constructor returns — no additional synchronization is required by callers.
 */
public class ServerStats
{
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Cumulative TCP connections accepted since server startup (monotonically increasing). */
    public final long totalConnectionsAccepted;

    /** Number of clients actively connected at the moment this snapshot was taken. */
    public final int activeClients;

    /** Total broadcast operations dispatched since server startup. */
    public final long totalMessagesRelayed;

    /** Approximate cumulative bytes delivered to active clients since server startup. */
    public final long totalBytesSent;

    /** Wall-clock timestamp captured at construction, formatted as {@code yyyy-MM-dd HH:mm:ss.SSS}. */
    public final String timestamp;

    /**
     * Constructs a new snapshot from the supplied telemetry values.
     * The {@link #timestamp} is set to the current wall-clock time.
     *
     * @param totalConnectionsAccepted cumulative connection count; must be &ge; 0
     * @param activeClients            live client count at snapshot time; must be &ge; 0
     * @param totalMessagesRelayed     cumulative broadcast count; must be &ge; 0
     * @param totalBytesSent           approximate cumulative bytes delivered; must be &ge; 0
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
        this.timestamp                = LocalDateTime.now().format(FORMATTER);
    }

    /**
     * Returns a single-line summary suitable for structured log output.
     *
     * @return formatted telemetry string; never {@code null}
     */
    @Override
    public String toString()
    {
        return String.format(
            "[ServerStats @ %s] Active: %d | TotalConnected: %d | MessagesRelayed: %d | BytesSent: %d",
            timestamp, activeClients, totalConnectionsAccepted, totalMessagesRelayed, totalBytesSent
        );
    }
}
