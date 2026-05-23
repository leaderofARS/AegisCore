import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global singleton registry that tracks every live {@link ClientHandler} in the AegisCore server.
 *
 * <p>This class is the central bookkeeping authority for all active client connections. Every
 * component that needs to look up, enumerate, or broadcast to connected clients goes through this
 * registry. It also maintains lifetime server-wide statistics (total connections accepted, messages
 * relayed, and bytes sent) that are surfaced via {@link #getStats()}.
 *
 * <h3>Singleton guarantee</h3>
 * The sole instance is created eagerly as a {@code static final} field. The JVM's class-loading
 * mechanism guarantees that the field is initialised exactly once and that the fully-constructed
 * object is visible to every thread before any code can call {@link #getInstance()}.  No
 * double-checked locking or {@code volatile} flag is needed.
 *
 * <h3>Thread safety</h3>
 * <ul>
 *   <li>The client map is a {@link ConcurrentHashMap}; concurrent {@code put}, {@code remove}, and
 *       iteration are all safe without an external lock.
 *   <li>The three numeric accumulators are {@link AtomicLong} fields, allowing lock-free CAS
 *       increments that are far cheaper than {@code synchronized} blocks under high concurrency.
 *   <li>{@link #BroadcastMessage(String)} iterates over the map's weakly-consistent view — it will
 *       never throw {@link java.util.ConcurrentModificationException} and dead entries self-evict
 *       asynchronously via {@code ClientHandler.forceDisconnect()}.
 * </ul>
 *
 * <p>This class is unconditionally thread-safe.
 */
public class SharedClientRegistry
{
    // -------------------------------------------------------------------------
    // SINGLETON
    // -------------------------------------------------------------------------

    /**
     * The sole instance of this registry.
     *
     * <p>Eagerly initialised by the class loader; safe for publication to any thread without
     * additional synchronisation.
     */
    private static final SharedClientRegistry instance = new SharedClientRegistry();

    /** Private constructor — prevents external or subclass instantiation. */
    private SharedClientRegistry() {}

    /**
     * Returns the single, process-wide {@code SharedClientRegistry} instance.
     *
     * @return the singleton registry; never {@code null}
     */
    public static SharedClientRegistry getInstance() { return instance; }

    // -------------------------------------------------------------------------
    // STATE
    // -------------------------------------------------------------------------

    /**
     * Map from client-ID strings to their active {@link ClientHandler} objects.
     *
     * <p>{@link ConcurrentHashMap} was chosen because it allows concurrent {@code put},
     * {@code remove}, and full-map iteration without holding a single global lock.  Internally it
     * uses CAS operations and fine-grained bin-level locking (Java 8+), making it the right
     * trade-off for a chat server where connections arrive and depart continuously while broadcast
     * iteration is also in progress.
     */
    private final ConcurrentHashMap<String, ClientHandler> connectedClients
            = new ConcurrentHashMap<>();

    /**
     * Monotonically increasing count of every connection ever accepted by this server process.
     *
     * <p>{@link AtomicLong} enables lock-free increment via a single CAS instruction, which is
     * vastly cheaper than a {@code synchronized} block when many threads are accepting connections
     * simultaneously.  The value never decreases; disconnections do not decrement it.
     */
    private final AtomicLong totalConnectionsAccepted = new AtomicLong(0);

    /**
     * Running total of broadcast operations dispatched since server start.
     *
     * <p>Incremented once per {@link #BroadcastMessage(String)} call regardless of how many
     * clients actually received the message.  {@link AtomicLong} for the same lock-free reasons as
     * {@link #totalConnectionsAccepted}.
     */
    private final AtomicLong totalMessagesRelayed = new AtomicLong(0);

    /**
     * Cumulative byte-count of data confirmed delivered to active clients.
     *
     * <p>Only bytes sent to clients whose {@link ClientHandler#isActive()} flag is {@code true}
     * immediately after {@link ClientHandler#sendMessage(String)} returns are counted here.
     * Attempted-but-failed sends are not included, so this figure represents confirmed throughput.
     * {@link AtomicLong} allows concurrent {@link AtomicLong#addAndGet(long)} calls from parallel
     * broadcast threads without a lock.
     */
    private final AtomicLong totalBytesSent = new AtomicLong(0);

    // -------------------------------------------------------------------------
    // CONNECTION LIFECYCLE
    // -------------------------------------------------------------------------

    /**
     * Registers a newly connected client in the registry and updates lifetime statistics.
     *
     * <p>The {@code clientId} is used as the map key for all subsequent lookups and removals.
     * After insertion the method logs both the new client's identity and a snapshot of current
     * active / total-ever counts.
     *
     * @param clientId a non-null, non-empty string uniquely identifying the client for the lifetime
     *                 of this connection (e.g., an assigned UUID or IP:port string)
     * @param handler  the non-null {@link ClientHandler} that owns the client's socket and I/O
     *                 threads
     */
    public void addClient(String clientId, ClientHandler handler)
    {
        connectedClients.put(clientId, handler);

        // Atomically increment and capture the new lifetime total so the log line is consistent
        // with the value stored in totalConnectionsAccepted.
        long totalEver = totalConnectionsAccepted.incrementAndGet();

        Logger.logRegistry("Client connected: " + clientId);
        Logger.logRegistry(
            "Active: " + getClientCount() +
            " | Total ever connected: " + totalEver
        );
    }

    /**
     * Removes a client from the registry upon disconnection.
     *
     * <p>If {@code clientId} is not currently in the map (e.g., it was already evicted by a
     * concurrent broadcast) this method is a safe no-op — {@link ConcurrentHashMap#remove} is
     * idempotent.  After removal the method logs the departed client's identity and a fresh
     * active-count snapshot.
     *
     * @param clientId the non-null identifier of the client to deregister; must match the value
     *                 passed to {@link #addClient(String, ClientHandler)} at connect time
     */
    public void removeClient(String clientId)
    {
        connectedClients.remove(clientId);

        Logger.logRegistry("Client disconnected: " + clientId);
        Logger.logRegistry(
            "Active: " + getClientCount() +
            // Read lifetime total without incrementing — disconnection does not change it.
            " | Total ever connected: " + totalConnectionsAccepted.get()
        );
    }

    // -------------------------------------------------------------------------
    // LOOKUP & INSPECTION
    // -------------------------------------------------------------------------

    /**
     * Returns the {@link ClientHandler} associated with the given client ID, or {@code null} if no
     * such client is currently registered.
     *
     * @param clientId the non-null identifier of the client to look up
     * @return the live {@link ClientHandler} for {@code clientId}, or {@code null} if the client
     *         is not connected
     */
    public ClientHandler getClient(String clientId)
    {
        return connectedClients.get(clientId);
    }

    /**
     * Returns the number of clients currently registered in the map.
     *
     * <p>Because {@link ConcurrentHashMap#size()} is not guaranteed to be perfectly consistent
     * with concurrent modifications, the returned value is a best-effort snapshot.  It is suitable
     * for logging and statistics but should not be used for coordinating critical-section access.
     *
     * @return the approximate number of active client connections; never negative
     */
    public int getClientCount()
    {
        return connectedClients.size();
    }

    /**
     * Logs the set of client IDs that are currently registered.
     *
     * <p>Uses the map's {@code keySet()} view, which reflects the state at the moment the log
     * line is formatted.  Intended for administrative / debugging output only.
     */
    public void showConnectedClients()
    {
        Logger.logRegistry("Currently connected clients: " + connectedClients.keySet());
    }

    // -------------------------------------------------------------------------
    // BROADCAST
    // -------------------------------------------------------------------------

    /**
     * Delivers {@code message} to every client currently in the registry and updates byte / relay
     * statistics.
     *
     * <p>The method iterates over {@link ConcurrentHashMap}'s weakly-consistent view of values.
     * This means:
     * <ul>
     *   <li>Iteration never throws {@link java.util.ConcurrentModificationException}.
     *   <li>Clients that connect or disconnect during the loop may or may not be included —
     *       that is acceptable; dead handlers self-evict via {@code forceDisconnect()}.
     * </ul>
     *
     * <p>For each client, the method calls {@link ClientHandler#sendMessage(String)} and then
     * reads the handler's {@link ClientHandler#isActive()} flag — a volatile read, requiring no
     * lock — to decide whether the send actually reached the client.  Only confirmed deliveries
     * contribute to {@link #totalBytesSent}.
     *
     * <p>If any individual send throws an unchecked exception the error is logged and iteration
     * continues so that one bad client cannot block all others from receiving the message.
     *
     * @param message the non-null text payload to relay to all connected clients; its UTF-8
     *                byte-length is used for byte-count accounting
     */
    public void BroadcastMessage(String message)
    {
        // Count every broadcast attempt, regardless of how many clients succeed.
        totalMessagesRelayed.incrementAndGet();

        // Compute byte-length once so every successful delivery can addAndGet without re-encoding.
        int messageBytes = message.getBytes().length;

        int attempted  = 0;
        int successful = 0;

        for (ClientHandler handler : connectedClients.values())
        {
            attempted++;
            try {
                handler.sendMessage(message);

                // isActive() is a volatile read on the ClientHandler — no lock needed.
                // A false return means sendMessage() triggered forceDisconnect() internally,
                // so the bytes never reached the client's socket buffer.
                if (handler.isActive()) {
                    totalBytesSent.addAndGet(messageBytes);
                    successful++;
                } else {
                    Logger.logRegistryError(
                        "[BROADCAST] Dead client evicted mid-broadcast: " +
                        handler.getClientId() + " — message not delivered."
                    );
                }

            } catch (Exception e) {
                // Swallow per-client exceptions so one misbehaving handler cannot prevent the
                // remaining clients from receiving the broadcast.
                Logger.logRegistryError(
                    "[BROADCAST] Unexpected failure for client " +
                    handler.getClientId() + ": " + e.getMessage()
                );
            }
        }

        // Warn operators whenever the broadcast was not fully delivered so they can investigate
        // dead connections that have not yet self-evicted.
        if (successful < attempted) {
            Logger.logRegistryError(
                "[BROADCAST] Partial delivery: " + successful + "/" + attempted +
                " clients received the message."
            );
        }
    }

    // -------------------------------------------------------------------------
    // STATISTICS
    // -------------------------------------------------------------------------

    /**
     * Returns a consistent point-in-time snapshot of server-wide statistics.
     *
     * <p>Each field is read from its respective {@link AtomicLong} in a single load instruction.
     * The returned {@link ServerStats} object is immutable; callers may hold references to it
     * without affecting the live counters.
     *
     * @return a non-null {@link ServerStats} value object capturing the current values of
     *         total connections accepted, active connections, messages relayed, and bytes sent
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