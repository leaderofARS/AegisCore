import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global singleton registry that tracks every live {@link ClientHandler} in the AegisCore server.
 *
 * <p>All components that need to look up, enumerate, or broadcast to connected clients go through
 * this registry. It also maintains server-wide lifetime statistics surfaced via {@link #getStats()}.
 *
 * <p><b>Singleton:</b> Eagerly initialised by the class loader; safe for publication to any thread
 * without additional synchronisation.
 *
 * <p><b>Thread safety:</b>
 * <ul>
 *   <li>The client map is a {@link ConcurrentHashMap} — concurrent put, remove, and iteration are
 *       all safe without an external lock.</li>
 *   <li>Numeric accumulators are {@link AtomicLong} fields, allowing lock-free CAS increments.</li>
 *   <li>{@link #BroadcastMessage(String)} iterates over a weakly-consistent view and will never
 *       throw {@link java.util.ConcurrentModificationException}.</li>
 * </ul>
 */
public class SharedClientRegistry
{
    private static final SharedClientRegistry instance = new SharedClientRegistry();
    private SharedClientRegistry() {}

    /** Returns the singleton registry instance. */
    public static SharedClientRegistry getInstance() { return instance; }

    private final ConcurrentHashMap<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();
    private final AtomicLong totalConnectionsAccepted = new AtomicLong(0);
    private final AtomicLong totalMessagesRelayed      = new AtomicLong(0);
    private final AtomicLong totalBytesSent            = new AtomicLong(0);

    /**
     * Registers a newly connected client and increments the lifetime connection counter.
     *
     * @param clientId unique identifier for this connection (e.g., remote IP:port)
     * @param handler  the handler that owns the client's socket and I/O
     */
    public void addClient(String clientId, ClientHandler handler)
    {
        connectedClients.put(clientId, handler);
        long totalEver = totalConnectionsAccepted.incrementAndGet();
        Logger.logRegistry("Client connected: " + clientId);
        Logger.logRegistry("Active: " + getClientCount() + " | Total ever connected: " + totalEver);
    }

    /**
     * Removes a client from the registry on disconnection. Safe to call when the client
     * has already been evicted by a concurrent broadcast — {@link ConcurrentHashMap#remove}
     * is idempotent.
     *
     * @param clientId the identifier passed to {@link #addClient} at connect time
     */
    public void removeClient(String clientId)
    {
        connectedClients.remove(clientId);
        Logger.logRegistry("Client disconnected: " + clientId);
        Logger.logRegistry("Active: " + getClientCount() + " | Total ever connected: " + totalConnectionsAccepted.get());
    }

    /**
     * Returns the {@link ClientHandler} for the given client ID, or {@code null} if not connected.
     *
     * @param clientId the identifier to look up
     * @return the live handler, or {@code null}
     */
    public ClientHandler getClient(String clientId)
    {
        return connectedClients.get(clientId);
    }

    /**
     * Returns an approximate count of currently connected clients.
     * Suitable for logging and statistics; not reliable for critical-section coordination.
     *
     * @return number of active client connections
     */
    public int getClientCount()
    {
        return connectedClients.size();
    }

    /** Logs the set of currently connected client IDs for diagnostic purposes. */
    public void showConnectedClients()
    {
        Logger.logRegistry("Currently connected clients: " + connectedClients.keySet());
    }

    /**
     * Delivers {@code message} to every client currently in the registry and updates
     * byte and relay statistics.
     *
     * <p>Iterates the map's weakly-consistent view — iteration never throws
     * {@link java.util.ConcurrentModificationException}, and transiently absent or
     * newly added clients during iteration are acceptable. Dead handlers self-evict
     * via {@link ClientHandler#forceDisconnect()}.
     *
     * <p>Per-client exceptions are caught and logged individually so one failing client
     * cannot block delivery to the rest. A partial-delivery warning is emitted only
     * when the failure rate reaches or exceeds 50%, avoiding log noise from normal
     * transient churn.
     *
     * @param message the text payload to relay; its byte length is used for accounting
     */
    public void BroadcastMessage(String message)
    {
        totalMessagesRelayed.incrementAndGet();
        int messageBytes = message.getBytes().length;
        int attempted    = 0;
        int successful   = 0;

        for (ClientHandler handler : connectedClients.values())
        {
            attempted++;
            try {
                handler.sendMessage(message);
                if (handler.isActive()) {
                    totalBytesSent.addAndGet(messageBytes);
                    successful++;
                } else {
                    if (handler.claimEvictionLog()) {
                        Logger.logRegistryError(
                            "[BROADCAST] Dead client evicted mid-broadcast: " +
                            handler.getClientId() + " — message not delivered."
                        );
                    }
                }
            } catch (Exception e) {
                Logger.logRegistryError(
                    "[BROADCAST] Unexpected failure for client " +
                    handler.getClientId() + ": " + e.getMessage()
                );
            }
        }

        if (attempted > 0 && successful < attempted) {
            double failureRate = (double)(attempted - successful) / attempted;
            if (failureRate >= 0.5) {
                Logger.logRegistryError(
                    "[BROADCAST] Partial delivery: " + successful + "/" + attempted +
                    " clients received the message (" +
                    String.format("%.0f", failureRate * 100) + "% failure rate)."
                );
            }
        }
    }

    /**
     * Sends a shutdown notice to every connected client and calls
     * {@link ClientHandler#forceDisconnect()} on each, unblocking their read loops so
     * threads can proceed to cleanup. Called by {@code Server.shutdown()} after the
     * server socket has been closed.
     *
     * <p>Per-handler exceptions are caught individually to ensure all clients are
     * signalled even if one fails.
     */
    public void shutdownAllClients()
    {
        int count = connectedClients.size();
        Logger.logRegistry("[SHUTDOWN] Initiating graceful disconnect for " + count + " connected client(s).");

        int signalled = 0;
        for (ClientHandler handler : connectedClients.values())
        {
            try {
                handler.sendMessage("[SERVER] Server is shutting down. Goodbye.");
                handler.forceDisconnect();
                signalled++;
            } catch (Exception e) {
                Logger.logRegistryError(
                    "[SHUTDOWN] Failed to disconnect client " + handler.getClientId() + ": " + e.getMessage()
                );
            }
        }

        Logger.logRegistry("[SHUTDOWN] Shutdown signal sent to " + signalled + "/" + count + " client(s).");
    }

    /**
     * Returns an immutable point-in-time snapshot of server-wide statistics.
     *
     * @return a {@link ServerStats} capturing current totals; never {@code null}
     */
    public ServerStats getStats()
    {
        return new ServerStats(
            totalConnectionsAccepted.get(),
            getClientCount(),
            totalMessagesRelayed.get(),
            totalBytesSent.get()
        );
    }
}