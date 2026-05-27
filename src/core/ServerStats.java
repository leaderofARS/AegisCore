package core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Immutable, point-in-time snapshot of AegisCore server telemetry.
 *
 * <p>Produced by assembling live values from {@code PlayerRegistry} and
 * {@code RoomRegistry}. Because every field is {@code public final}, the JMM
 * guarantees safe publication to all threads once the constructor returns.
 */
public final class ServerStats {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Cumulative TCP connections accepted since startup (monotonically increasing). */
    public final long totalConnectionsAccepted;

    /** Players actively connected at snapshot time. */
    public final int activePlayers;

    /** Open lobby rooms at snapshot time. */
    public final int activeRooms;

    /** Players currently waiting in the matchmaking queue. */
    public final int queueSize;

    /** Wall-clock timestamp formatted as {@code yyyy-MM-dd HH:mm:ss.SSS}. */
    public final String timestamp;

    /**
     * Constructs a new telemetry snapshot.
     *
     * @param totalConnectionsAccepted cumulative connection count
     * @param activePlayers            live player count
     * @param activeRooms              live room count
     * @param queueSize                players in matchmaking queue
     */
    public ServerStats(long totalConnectionsAccepted, int activePlayers, int activeRooms, int queueSize) {
        this.totalConnectionsAccepted = totalConnectionsAccepted;
        this.activePlayers            = activePlayers;
        this.activeRooms              = activeRooms;
        this.queueSize                = queueSize;
        this.timestamp                = LocalDateTime.now().format(FORMATTER);
    }

    @Override
    public String toString() {
        return String.format(
            "[AegisCore @ %s] Players: %d | Rooms: %d | Queue: %d | TotalConnections: %d",
            timestamp, activePlayers, activeRooms, queueSize, totalConnectionsAccepted
        );
    }
}
