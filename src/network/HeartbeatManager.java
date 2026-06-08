package network;

import core.Logger;
import player.Player;
import player.PlayerRegistry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Detects and evicts dead ("zombie") TCP connections using a PING/PONG heartbeat.
 *
 * <p>Every {@value #PING_INTERVAL_SECONDS} seconds, a {@code [PING]} message is sent
 * to every active player. The server then waits {@value #PONG_TIMEOUT_SECONDS} seconds
 * for a {@code [PONG]} response. Players who do not respond within the window are
 * forcibly disconnected.
 *
 * <p>Call {@link #recordPong(String)} from the command router when a PONG command
 * arrives from a client.
 *
 * <p>Thread-safe singleton. Runs on a shared {@link ScheduledExecutorService}.
 */
public final class HeartbeatManager {

    private static final HeartbeatManager INSTANCE = new HeartbeatManager();

    /** Seconds between each PING broadcast. */
    private static final int PING_INTERVAL_SECONDS = 15;
    /** Seconds after PING to wait before evicting non-responding clients. */
    private static final int PONG_TIMEOUT_SECONDS  = 5;

    private final ConcurrentHashMap<String, Long> lastPongNanos = new ConcurrentHashMap<>();
    private final ScheduledExecutorService        scheduler;
    private volatile boolean                      running       = true;

    private HeartbeatManager() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("HeartbeatManager");
            t.setDaemon(true);
            return t;
        });
    }

    /** Returns the singleton {@code HeartbeatManager}. */
    public static HeartbeatManager getInstance() {
        return INSTANCE;
    }

    /**
     * Starts the heartbeat loop. Call once from {@code Server.main()}.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(this::heartbeatCycle,
            PING_INTERVAL_SECONDS, PING_INTERVAL_SECONDS, TimeUnit.SECONDS);
        Logger.logServer("HeartbeatManager: started (interval=" + PING_INTERVAL_SECONDS + "s, timeout=" + PONG_TIMEOUT_SECONDS + "s).");
    }

    /**
     * Records a PONG response from the given session, resetting its eviction timer.
     *
     * @param sessionId the session that responded to PING
     */
    public void recordPong(String sessionId) {
        lastPongNanos.put(sessionId, System.nanoTime());
    }

    /**
     * Removes a session from heartbeat tracking on clean disconnect.
     *
     * @param sessionId the disconnecting session
     */
    public void evict(String sessionId) {
        lastPongNanos.remove(sessionId);
    }

    /** Stops the heartbeat scheduler. */
    public void stop() {
        running = false;
        scheduler.shutdownNow();
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void heartbeatCycle() {
        if (!running) return;
        PlayerRegistry registry = PlayerRegistry.getInstance();
        long pingNanos = System.nanoTime();

        // Phase 1: send PING to all active players
        for (Player p : registry.getAllPlayers()) {
            lastPongNanos.putIfAbsent(p.getSessionId(), pingNanos);
            p.send("[PING]");
        }

        // Phase 2: wait for PONG_TIMEOUT_SECONDS
        try {
            Thread.sleep(PONG_TIMEOUT_SECONDS * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        // Phase 3: evict anyone whose last pong predates the ping by >threshold
        long deadlineNanos = pingNanos - 1_000_000L; // 1ms grace
        for (Player p : registry.getAllPlayers()) {
            Long lastPong = lastPongNanos.get(p.getSessionId());
            if (lastPong != null && lastPong < deadlineNanos) {
                if (p.getHandler().isActive()) {
                    Logger.logClientHandlerError("HeartbeatManager: evicting dead socket " + p.getSessionId());
                    p.getHandler().forceDisconnect();
                }
                lastPongNanos.remove(p.getSessionId());
            }
        }
    }
}
