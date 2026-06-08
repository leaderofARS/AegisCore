package security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Guards against connection floods by limiting simultaneous connections per IP address.
 *
 * <p>Each accepted socket must call {@link #allowConnection(String)} before a
 * {@link server.ClientHandler} is spawned. On disconnect, call {@link #releaseConnection(String)}
 * to decrement the counter for that IP.
 *
 * <p>Thread-safe: uses lock-free {@link AtomicInteger} per IP entry.
 */
public final class ConnectionGuard {

    /** Maximum simultaneous connections allowed from one IP address. */
    private static final int MAX_PER_IP = 10;

    private static final ConnectionGuard INSTANCE = new ConnectionGuard();

    private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    private ConnectionGuard() {}

    /** Returns the singleton {@code ConnectionGuard}. */
    public static ConnectionGuard getInstance() {
        return INSTANCE;
    }

    /**
     * Attempts to register a new connection from the given IP.
     *
     * @param ip the remote IP address string
     * @return {@code true} if the connection is allowed; {@code false} if the IP is at its limit
     */
    public boolean allowConnection(String ip) {
        AtomicInteger count = counts.computeIfAbsent(ip, k -> new AtomicInteger(0));
        int current = count.incrementAndGet();
        if (current > MAX_PER_IP) {
            count.decrementAndGet();
            return false;
        }
        return true;
    }

    /**
     * Releases a connection slot for the given IP on disconnect. Idempotent.
     *
     * @param ip the remote IP address string
     */
    public void releaseConnection(String ip) {
        AtomicInteger count = counts.get(ip);
        if (count != null) {
            int val = count.decrementAndGet();
            if (val <= 0) {
                counts.remove(ip);
            }
        }
    }

    /**
     * Returns the current number of active connections from the given IP.
     *
     * @param ip the remote IP address string
     * @return connection count (0 if none tracked)
     */
    public int getConnectionCount(String ip) {
        AtomicInteger count = counts.get(ip);
        return count == null ? 0 : count.get();
    }
}
