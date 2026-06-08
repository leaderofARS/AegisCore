package core;

import java.lang.management.*;

/**
 * Collects a snapshot of server, room, matchmaking, and JVM runtime metrics.
 *
 * <p>Singleton. Call {@link #initialize} once from {@code Server.main()} before
 * the first {@link #collect()} call.
 */
public final class MetricsCollector {

    private static final MetricsCollector INSTANCE = new MetricsCollector();

    private volatile player.PlayerRegistry    playerRegistry;
    private volatile room.RoomRegistry        roomRegistry;
    private volatile matchmaking.MatchmakingQueue matchmakingQueue;

    private MetricsCollector() {}

    /** Returns the singleton {@code MetricsCollector}. */
    public static MetricsCollector getInstance() { return INSTANCE; }

    /**
     * Injects the live server subsystems needed for metric collection.
     * Must be called once during server startup.
     *
     * @param playerRegistry    player registry
     * @param roomRegistry      room registry
     * @param matchmakingQueue  matchmaking queue
     */
    public void initialize(player.PlayerRegistry playerRegistry,
                           room.RoomRegistry roomRegistry,
                           matchmaking.MatchmakingQueue matchmakingQueue) {
        this.playerRegistry   = playerRegistry;
        this.roomRegistry     = roomRegistry;
        this.matchmakingQueue = matchmakingQueue;
    }

    /**
     * Collects and returns a point-in-time metrics snapshot.
     *
     * @return current {@link MetricsSnapshot}
     */
    public MetricsSnapshot collect() {
        MemoryMXBean  mem     = ManagementFactory.getMemoryMXBean();
        ThreadMXBean  threads = ManagementFactory.getThreadMXBean();
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();

        long heapUsed = mem.getHeapMemoryUsage().getUsed();
        long heapMax  = mem.getHeapMemoryUsage().getMax();
        int  threadCt = threads.getThreadCount();
        long uptime   = runtime.getUptime() / 1000; // ms → sec

        int activePlayers = playerRegistry  != null ? playerRegistry.getPlayerCount()    : 0;
        long totalConn    = playerRegistry  != null ? playerRegistry.getTotalConnections() : 0;
        int activeRooms   = roomRegistry    != null ? roomRegistry.getActiveRoomCount()  : 0;
        int queueSize     = matchmakingQueue != null ? matchmakingQueue.getQueueSize()   : 0;

        return new MetricsSnapshot(activePlayers, totalConn, activeRooms, queueSize,
            heapUsed, heapMax, threadCt, uptime);
    }

    // -----------------------------------------------------------------------

    /**
     * An immutable point-in-time metrics snapshot.
     *
     * @param activePlayers    current online player count
     * @param totalConnections cumulative connections since startup
     * @param activeRooms      rooms in WAITING or READY_CHECK state
     * @param queueSize        players waiting in matchmaking queue
     * @param heapUsedBytes    JVM heap bytes currently in use
     * @param heapMaxBytes     JVM heap maximum configured bytes
     * @param threadCount      total JVM thread count
     * @param uptimeSeconds    server uptime in seconds
     */
    public record MetricsSnapshot(
        int  activePlayers,
        long totalConnections,
        int  activeRooms,
        int  queueSize,
        long heapUsedBytes,
        long heapMaxBytes,
        int  threadCount,
        long uptimeSeconds
    ) {
        /** Formats this snapshot as Prometheus plain-text exposition format. */
        public String toPrometheusText() {
            return """
                aegiscore_players_active %d
                aegiscore_connections_total %d
                aegiscore_rooms_active %d
                aegiscore_queue_size %d
                aegiscore_heap_used_bytes %d
                aegiscore_heap_max_bytes %d
                aegiscore_thread_count %d
                aegiscore_uptime_seconds %d
                """.formatted(activePlayers, totalConnections, activeRooms, queueSize,
                              heapUsedBytes, heapMaxBytes, threadCount, uptimeSeconds);
        }

        /** Formats this snapshot as a compact JSON object. */
        public String toJson() {
            return """
                {"status":"UP","players":%d,"rooms":%d,"queue":%d,"connections":%d,\
                "heapUsedBytes":%d,"heapMaxBytes":%d,"threads":%d,"uptimeSeconds":%d}
                """.formatted(activePlayers, activeRooms, queueSize, totalConnections,
                              heapUsedBytes, heapMaxBytes, threadCount, uptimeSeconds).strip();
        }
    }
}
