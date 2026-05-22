import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SharedClientRegistry — Level 2.4: Synchronization Hardening
 *
 * Global singleton registry tracking every currently connected client.
 * Provides thread-safe client registration, lookup, removal, and broadcast.
 * Adds lock-free atomic telemetry counters for server observability.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THREAD SAFETY ARCHITECTURE
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   THREE concurrent operations can hit this class simultaneously:
 *     1. addClient()       — a new connection is accepted (server accept-loop thread)
 *     2. removeClient()    — a client disconnects    (that client's handler thread)
 *     3. BroadcastMessage()— a client sends a message (any client's handler thread)
 *
 *   Strategy: ConcurrentHashMap handles structural concurrency (add/remove/iterate)
 *   without any external synchronization. Each individual operation on the map
 *   is atomic. The weakly-consistent iterator used in BroadcastMessage() is
 *   intentional and correct — see BroadcastMessage() javadoc for the reasoning.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY NOT SYNCHRONIZE THE ENTIRE REGISTRY?
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   A naive approach wraps every method in synchronized(this):
 *
 *       synchronized void addClient(...)    { map.put(...);   }
 *       synchronized void removeClient(...) { map.remove(...); }
 *       synchronized void BroadcastMessage(...) {
 *           for (handler : map.values()) handler.sendMessage(msg);
 *       }
 *
 *   The moment BroadcastMessage() holds the global lock while iterating,
 *   NO client can connect or disconnect until the broadcast finishes.
 *   Under 100 clients, a broadcast calls sendMessage() 100 times. Each
 *   sendMessage() may block waiting for a slow client's network buffer.
 *   The entire server stalls for the duration. Throughput collapses.
 *
 *   ConcurrentHashMap + per-client locks (in ClientHandler.sendMessage())
 *   eliminates this bottleneck entirely — see BroadcastMessage() for detail.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ATOMIC COUNTERS — WHY AtomicLong, NOT synchronized int
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   For simple numeric accumulators (totalConnections++, totalMessages++) the
 *   cost of acquiring a monitor lock is disproportionate. AtomicLong uses
 *   CPU-level Compare-And-Swap (CAS) instructions — hardware-atomic, no OS
 *   context switch, effectively zero contention overhead at normal concurrency.
 *
 *   Rule of thumb:
 *     • Shared numeric counter  → AtomicLong / AtomicInteger
 *     • Shared complex state    → synchronized block or ReentrantLock
 */
public class SharedClientRegistry
{
    // ─────────────────────────────────────────────────────────────────────────
    // SINGLETON
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Eagerly initialized singleton.
     *
     * The JVM class-loading specification guarantees that static final fields
     * are initialized exactly once, and that initialization is visible to all
     * threads before any thread can access the class. No synchronized needed.
     */
    private static final SharedClientRegistry instance = new SharedClientRegistry();

    /** Private constructor — prevents external instantiation. */
    private SharedClientRegistry() {}

    public static SharedClientRegistry getInstance() {
        return instance;
    }


    // ─────────────────────────────────────────────────────────────────────────
    // CONNECTED CLIENT MAP
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Maps clientId → ClientHandler for every currently active connection.
     *
     * ConcurrentHashMap vs HashMap:
     *   HashMap is NOT thread-safe. Under concurrent access it can:
     *     - Return stale or null values for existing keys.
     *     - Enter an infinite loop during rehashing (Java 6 bug, still possible).
     *     - Cause data loss silently.
     *
     *   ConcurrentHashMap uses internal segment locking (Java 8+: CAS + bin locks)
     *   to allow concurrent reads and writes without a global lock.
     *   put(), remove(), get(), size(), and values() are all safe to call
     *   from multiple threads simultaneously.
     */
    private final ConcurrentHashMap<String, ClientHandler> connectedClients
            = new ConcurrentHashMap<>();


    // ─────────────────────────────────────────────────────────────────────────
    // ATOMIC TELEMETRY COUNTERS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Total client connections accepted since server start.
     *
     * Monotonic — only incremented, never decremented.
     * Gives a cumulative view of server load independent of current active count.
     *
     * AtomicLong.incrementAndGet() is a single hardware CAS instruction.
     * No lock acquisition, no thread scheduling, no OS involvement.
     */
    private final AtomicLong totalConnectionsAccepted = new AtomicLong(0);

    /**
     * Total broadcast events dispatched since server start.
     *
     * Incremented ONCE per BroadcastMessage() call — not once per recipient.
     * Counts "how many times a message was sent into the system," not deliveries.
     * Deliveries = totalMessagesRelayed × average recipient count (derivable).
     */
    private final AtomicLong totalMessagesRelayed = new AtomicLong(0);

    /**
     * Approximate total bytes written to all client output streams since start.
     *
     * Approximation: uses message.getBytes().length × recipient count.
     * True byte count would require tracking PrintWriter flush bytes directly.
     * This approximation is sufficient for throughput profiling and benchmarking.
     *
     * addAndGet() is also a single CAS instruction — safe for high-frequency use.
     */
    private final AtomicLong totalBytesSent = new AtomicLong(0);


    // ─────────────────────────────────────────────────────────────────────────
    // REGISTRY OPERATIONS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Registers a newly connected client in the shared map.
     *
     * Thread safety:
     *   ConcurrentHashMap.put() is internally atomic — no external lock needed.
     *   AtomicLong.incrementAndGet() is a single CAS instruction.
     *   Logger.logRegistry() is synchronized internally (see Logger).
     *
     * @param clientId  Unique identifier string for the client (IP:port).
     * @param handler   The ClientHandler instance managing that client's session.
     */
    public void addClient(String clientId, ClientHandler handler)
    {
        // Register the client in the concurrent map — thread-safe, no lock needed.
        connectedClients.put(clientId, handler);

        // Increment the lifetime connection counter atomically.
        // incrementAndGet() returns the new value — useful for logging.
        long totalEver = totalConnectionsAccepted.incrementAndGet();

        Logger.logRegistry("Client connected: " + clientId);
        Logger.logRegistry(
            "Active: " + getClientCount() +
            " | Total ever connected: " + totalEver
        );
    }

    /**
     * Unregisters a disconnected or exiting client from the shared map.
     *
     * Called from ClientHandler.cleanup() inside a finally block — this method
     * is guaranteed to execute even when the client disconnects abruptly.
     *
     * Thread safety: ConcurrentHashMap.remove() is internally atomic.
     *
     * @param clientId  The ID of the client to remove.
     */
    public void removeClient(String clientId)
    {
        // Remove the client atomically — ConcurrentHashMap handles concurrency.
        connectedClients.remove(clientId);

        Logger.logRegistry("Client disconnected: " + clientId);
        Logger.logRegistry(
            "Active: " + getClientCount() +
            " | Total ever connected: " + totalConnectionsAccepted.get()
        );
    }

    /**
     * Retrieves the handler for a specific client by their ID.
     *
     * @param clientId  The client ID to look up.
     * @return          The ClientHandler, or null if that client is not connected.
     */
    public ClientHandler getClient(String clientId)
    {
        return connectedClients.get(clientId);
    }

    /**
     * Returns the number of currently active client connections.
     *
     * ConcurrentHashMap.size() is a live, consistent read of the current count.
     * Note: this is a snapshot — by the time the caller reads the value,
     * a client may have connected or disconnected. Acceptable for telemetry.
     */
    public int getClientCount()
    {
        return connectedClients.size();
    }

    /** Logs all currently connected client IDs to the registry log. */
    public void showConnectedClients()
    {
        Logger.logRegistry("Currently connected clients: " + connectedClients.keySet());
    }


    // ─────────────────────────────────────────────────────────────────────────
    // BROADCAST — FINE-GRAINED LOCKING IN ACTION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Distributes a message to every currently connected client.
     *
     * ── WHAT FINE-GRAINED LOCKING MEANS HERE ────────────────────────────────
     *
     *   This method holds NO global lock at any point during its execution.
     *
     *   The iteration over connectedClients.values() is weakly-consistent:
     *     - Clients added during iteration MAY or MAY NOT receive this message
     *       (depends on whether they are added before or after their slot is visited).
     *     - Clients removed during iteration are either skipped entirely, or their
     *       sendMessage() call hits the "socket.isClosed()" guard and no-ops safely.
     *   This is the correct trade-off for broadcast: no frozen snapshot needed.
     *
     *   Each handler.sendMessage() call acquires only THAT client's intrinsic lock:
     *
     *       Thread A (broadcasting) → acquires Client X's lock → writes → releases
     *       Thread B (broadcasting) → acquires Client Y's lock → writes → releases
     *       ↑ These happen in parallel. No mutual exclusion between different clients.
     *
     *   Only writes to the SAME client are serialized.
     *   That is fine-grained locking. That is why throughput scales.
     *
     * ── FAILURE ISOLATION ────────────────────────────────────────────────────
     *
     *   If one client has a broken connection, sendMessage() on that handler
     *   logs the error but does NOT throw. The broadcast continues to all
     *   remaining healthy clients uninterrupted.
     *
     * @param message  The message string to send to all connected clients.
     */
    public void BroadcastMessage(String message)
    {
        // Increment the broadcast event counter atomically — one CAS instruction.
        totalMessagesRelayed.incrementAndGet();

        // Pre-compute the byte length once — reused for each recipient.
        // message.getBytes() defaults to platform encoding; consistent for telemetry.
        int messageBytes = message.getBytes().length;

        // Iterate all currently known handlers.
        // weakly-consistent: safe under concurrent map modifications.
        for (ClientHandler handler : connectedClients.values())
        {
            try {
                // ── PER-CLIENT LOCK ──────────────────────────────────────────
                // sendMessage() is synchronized on the handler instance ("this").
                // This acquires ONLY that specific client's lock.
                // Other clients' broadcasts continue unblocked in parallel.
                handler.sendMessage(message);

                // Accumulate approximate bytes written across all streams.
                // addAndGet() is a single CAS — no lock needed.
                totalBytesSent.addAndGet(messageBytes);

            } catch (Exception e) {
                // Isolate: one broken client cannot abort the entire broadcast loop.
                Logger.logRegistryError(
                    "Broadcast delivery failed for a client: " + e.getMessage()
                );
            }
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // SERVER STATS SNAPSHOT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns an immutable snapshot of current server telemetry.
     *
     * AtomicLong.get() is a volatile read — guaranteed to return the latest
     * committed value without acquiring any lock. Safe to call from any thread.
     *
     * Returns a ServerStats object (immutable value type) — see ServerStats.java.
     *
     * @return  A point-in-time snapshot of server metrics.
     */
    public ServerStats getStats()
    {
        return new ServerStats(
            totalConnectionsAccepted.get(),  // long — atomic volatile read
            getClientCount(),                // int  — ConcurrentHashMap.size()
            totalMessagesRelayed.get(),      // long — atomic volatile read
            totalBytesSent.get()             // long — atomic volatile read
        );
    }
}