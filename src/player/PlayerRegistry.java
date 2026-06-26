package player;

import core.Logger;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global singleton registry tracking every connected {@link Player} in AegisCore.
 *
 * <p>All components that need to look up, enumerate, or broadcast to players go
 * through this registry. The underlying map is a {@link ConcurrentHashMap},
 * making concurrent registration, deregistration, and iteration safe without
 * an external lock. The lifetime connection counter uses {@link AtomicLong} for
 * lock-free increments.
 *
 * <p>Eagerly initialised by the class loader — safe for publication to any thread.
 */
public class PlayerRegistry {

    private static final PlayerRegistry instance = new PlayerRegistry();
    private PlayerRegistry() {}

    /** Returns the singleton {@code PlayerRegistry} instance. */
    public static PlayerRegistry getInstance() { return instance; }

    private final ConcurrentHashMap<String, Player> players             = new ConcurrentHashMap<>();
    private final AtomicLong                         totalConnections    = new AtomicLong(0);

    /**
     * Registers a newly connected player and increments the lifetime connection counter.
     *
     * @param player the player to register; must not be {@code null}
     */
    public void register(Player player) {
        players.put(player.getSessionId(), player);
        long total = totalConnections.incrementAndGet();
        Logger.logRegistry("Player registered: " + player.getSessionId() +
                           " | Active: " + players.size() + " | Total ever: " + total);
        cluster.ClusterManager.getInstance().syncLocalPlayer(player);
    }

    /**
     * Removes a player from the registry on disconnection. Idempotent.
     *
     * @param sessionId the session ID used at registration time
     */
    public void deregister(String sessionId) {
        Player player = players.remove(sessionId);
        if (player != null) {
            cluster.ClusterManager.getInstance().syncPlayerLeave(player);
        }
        Logger.logRegistry("Player deregistered: " + sessionId + " | Active: " + players.size());
    }

    /**
     * Returns the {@link Player} for the given session ID, or {@code null} if not found.
     *
     * @param sessionId session identifier to look up
     * @return the live player, or {@code null}
     */
    public Player getPlayer(String sessionId) { return players.get(sessionId); }

    /** Returns an approximate count of currently connected players. */
    public int getPlayerCount() { return players.size(); }

    /** Returns the cumulative number of connections accepted since server start. */
    public long getTotalConnections() { return totalConnections.get(); }

    /** Returns a weakly-consistent view of all currently connected players. */
    public Collection<Player> getAllPlayers() { return players.values(); }

    /**
     * Returns the player with the given display name, ignoring case, or null if not found.
     *
     * @param name display name to lookup
     * @return matching Player, or null
     */
    public Player getPlayerByName(String name) {
        if (name == null) return null;
        for (Player p : players.values()) {
            if (name.equalsIgnoreCase(p.getDisplayName())) {
                return p;
            }
        }
        return null;
    }

    /**
     * Returns all players currently in the lobby who have not sent a command for more than 30 seconds.
     *
     * @return collection of idle players
     */
    public Collection<Player> getIdlePlayers() {
        long limit = System.currentTimeMillis() - 30_000;
        java.util.List<Player> idle = new java.util.ArrayList<>();
        for (Player p : players.values()) {
            if (p.getStatus() == PlayerStatus.IN_LOBBY && p.getLastCommandAt() < limit) {
                idle.add(p);
            }
        }
        return idle;
    }

    /**
     * Broadcasts a message to all players currently in the lobby (status {@code IN_LOBBY}),
     * optionally excluding one session (e.g., the sender).
     *
     * @param message          text to deliver
     * @param excludeSessionId session to skip, or {@code null} to include everyone
     */
    public void broadcastLobby(String message, String excludeSessionId) {
        for (Player p : players.values()) {
            if (p.getStatus() == PlayerStatus.IN_LOBBY &&
                !p.getSessionId().equals(excludeSessionId)) {
                p.send(message);
            }
        }
    }

    /**
     * Broadcasts a message to every connected player regardless of status.
     *
     * @param message text to deliver to all sessions
     */
    public void broadcastAll(String message) {
        for (Player p : players.values()) { p.send(message); }
    }
}
