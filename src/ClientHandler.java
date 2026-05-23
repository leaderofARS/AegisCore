import java.io.*;
import java.net.*;

// -----------------------------------------------------------------------------
// ClientHandler — Level 2.4: Synchronization Hardening
// -----------------------------------------------------------------------------

/**
 * Manages the full lifecycle of a single connected client on a dedicated thread.
 *
 * <p>One {@code ClientHandler} instance is created per accepted {@link Socket}.
 * It is submitted to an executor (or started as a raw {@link Thread}) and owns
 * the read loop for its client. Other client threads may concurrently call
 * {@link #sendMessage} on this instance via
 * {@link SharedClientRegistry#BroadcastMessage}, so all output operations are
 * serialized through a single {@code synchronized} method.
 *
 * <h3>Threading model</h3>
 * <pre>
 *   Thread A — this client's own thread:  runs the read loop inside {@link #run()}.
 *   Thread B — a peer client's thread:    calls {@link #sendMessage} via broadcast.
 *   Thread C — yet another peer thread:   also calls {@link #sendMessage}.
 * </pre>
 * All three threads may attempt to write to the same {@link java.io.OutputStream}
 * concurrently. The single shared {@link PrintWriter} field, combined with the
 * {@code synchronized} keyword on {@link #sendMessage}, ensures that only one
 * thread writes at a time for this specific instance.
 *
 * <h3>Why a shared {@code PrintWriter} field (not a local one per call)</h3>
 * Prior to Level 2.4, {@code sendMessage()} created a fresh {@link PrintWriter}
 * on every invocation while {@code run()} held a separate local one — two
 * distinct wrappers around the same underlying {@link java.io.OutputStream}.
 * Even with {@code synchronized} on {@code sendMessage()}, the unsynchronized
 * writes from {@code run()} raced with broadcast writes, producing interleaved
 * byte sequences such as:
 * <pre>
 *   [Ali[Bob]]: hello
 * </pre>
 * The fix: one {@link PrintWriter} stored as a field; every write, including
 * those from {@code run()}, goes through {@link #sendMessage()}.
 *
 * <h3>Lock granularity</h3>
 * The monitor used is {@code this} — the individual {@code ClientHandler}
 * instance. Thread A writing to Client X holds <em>Client X's</em> lock.
 * Thread B writing to Client Y holds <em>Client Y's</em> lock. They never
 * contend with each other, so writes to distinct clients proceed in parallel.
 * Only concurrent writes targeting the <em>same</em> client are serialized.
 *
 * <p>This class is <strong>thread-safe</strong>.
 */
public class ClientHandler implements Runnable
{
    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    /** Registry shared across all active {@code ClientHandler} instances. Used
     *  to register/deregister this client and to broadcast messages. */
    private final SharedClientRegistry registry;

    /** The TCP socket accepted from the server's {@link java.net.ServerSocket}.
     *  Closed in {@link #closeSocket()} once the client disconnects or is
     *  forcibly removed. */
    private final Socket socket;

    /** Stable, human-readable identifier derived from the remote socket address.
     *  Computed once at construction and never mutated, so no synchronization
     *  is needed for reads. */
    private final String clientId;

    /** The sole {@link PrintWriter} wrapping this client's output stream.
     *  Initialized in {@link #run()} before {@code active} is set to
     *  {@code true}; after that point, every write goes through
     *  {@link #sendMessage(String)}, which is {@code synchronized}. */
    private PrintWriter output;

    /** Indicates whether this handler is ready to accept outbound messages.
     *  Declared {@code volatile} so that reads from threads that do not hold
     *  this object's monitor (e.g., a fast-path check in a polling loop) always
     *  observe the most recently written value. Authoritative mutations are
     *  performed inside {@code synchronized} blocks to guarantee
     *  check-then-act atomicity. */
    private volatile boolean active = false;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Constructs a {@code ClientHandler} for the given accepted socket.
     *
     * <p>The client identifier is derived from the socket's remote address so
     * that log messages are immediately traceable without an additional lookup.
     *
     * @param socket   the accepted client {@link Socket}; must be open and
     *                 connected
     * @param registry the shared registry used to track all active clients and
     *                 to relay broadcast messages
     */
    public ClientHandler(Socket socket, SharedClientRegistry registry)
    {
        this.socket   = socket;
        this.registry = registry;
        // Capture the remote address once; InetSocketAddress.toString() is
        // stable for the lifetime of the connection.
        this.clientId = socket.getRemoteSocketAddress().toString();
    }

    // -------------------------------------------------------------------------
    // Public accessors
    // -------------------------------------------------------------------------

    /**
     * Returns the stable string identifier for this client.
     *
     * <p>The identifier is the remote socket address captured at construction
     * time and never changes, so this method requires no synchronization.
     *
     * @return a non-null string identifying the remote endpoint, e.g.
     *         {@code "/127.0.0.1:54321"}
     */
    public String getClientId() { return clientId; }

    /**
     * Returns {@code true} if this handler is currently active and accepting
     * outbound messages.
     *
     * <p>Because {@code active} is {@code volatile}, callers that do not hold
     * this object's monitor will still observe the latest value. This method
     * is intended for diagnostic or administrative checks; authoritative
     * lifecycle decisions are made inside {@code synchronized} blocks.
     *
     * @return {@code true} if the handler is active; {@code false} if it has
     *         been shut down or not yet started
     */
    public boolean isActive() { return active; }

    // -------------------------------------------------------------------------
    // Outbound message dispatch
    // -------------------------------------------------------------------------

    /**
     * Sends a text message to this client over its output stream.
     *
     * <p>Acquires {@code this} object's monitor before writing so that
     * concurrent broadcast calls from peer threads are serialized. If the
     * underlying stream signals an error after the write, the socket is
     * forcibly closed via {@link #forceDisconnect()}.
     *
     * <p>This method is a no-op if the handler is not active, allowing callers
     * to invoke it without checking the lifecycle state themselves.
     *
     * @param message the line of text to deliver; a newline is appended by
     *                {@link PrintWriter#println(String)}
     */
    public synchronized void sendMessage(String message)
    {
        // Guard against writes to a handler that has already been torn down.
        if (!active) { return; }
        output.println(message);
        if (output.checkError()) {
            // The socket was closed on the remote end between the activity
            // check above and the actual write; tear down immediately rather
            // than leaving a zombie entry in the registry.
            Logger.logClientHandlerError(
                "[DEAD CLIENT] Write failed for " + clientId + " — socket broken. Forcing disconnect."
            );
            forceDisconnect();
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle control
    // -------------------------------------------------------------------------

    /**
     * Forcibly disconnects this client by marking it inactive and closing its
     * socket.
     *
     * <p>Acquires {@code this} object's monitor to guarantee that the
     * check-then-act sequence ({@code if (!active) … active = false}) is
     * atomic with respect to concurrent calls from broadcast threads or the
     * client's own read loop.
     *
     * <p>Idempotent: subsequent calls after the first are silently ignored.
     */
    synchronized void forceDisconnect()
    {
        // Prevent double-close if multiple threads race to disconnect.
        if (!active) { return; }
        active = false;
        closeSocket();
    }

    // -------------------------------------------------------------------------
    // Runnable entry point
    // -------------------------------------------------------------------------

    /**
     * Runs the read loop for this client on the calling thread.
     *
     * <p>Initializes the shared {@link PrintWriter}, registers this handler in
     * the {@link SharedClientRegistry}, then blocks on
     * {@link BufferedReader#readLine()} until the client disconnects, sends
     * {@code "exit"}, or an I/O error occurs. Each received line is
     * acknowledged back to the sender and broadcast to all other connected
     * clients. Cleanup is performed in a {@code finally} block regardless of
     * how the loop exits.
     *
     * <p>This method must be called from at most one thread per instance.
     */
    @Override
    public void run()
    {
        try {
            // Initialize output before marking active=true so that sendMessage()
            // never observes a null PrintWriter.
            output = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Publish active=true inside a synchronized block so that any
            // thread already waiting in sendMessage() sees a consistent state.
            synchronized (this) { active = true; }

            registry.addClient(clientId, this);
            sendMessage("[SERVER] Connected. Welcome to AegisCore server!");

            String message;
            // Blocks this thread until the client sends a line, closes the
            // connection, or the socket is closed by forceDisconnect().
            while ((message = input.readLine()) != null)
            {
                Logger.logClientHandler("Client " + clientId + " says: " + message);
                // Echo an acknowledgement before broadcasting so the sender
                // gets immediate confirmation regardless of broadcast latency.
                sendMessage("[ACK] Received: " + message);
                registry.BroadcastMessage("[" + clientId + "]: " + message);

                if (message.equalsIgnoreCase("exit")) {
                    // Honour the client's explicit disconnect request; break
                    // before the next readLine() attempt.
                    sendMessage("[SERVER] Goodbye. Connection closing.");
                    break;
                }
            }
            Logger.logClientHandler("Client " + clientId + " disconnected cleanly.");

        } catch (IOException e) {
            // Socket errors (reset, timeout, etc.) are non-fatal for the server;
            // log and fall through to cleanup.
            Logger.logClientHandlerError("I/O error for client " + clientId + ": " + e.getMessage());
        } finally {
            // Runs whether the loop exited normally, via break, or via exception.
            cleanup();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Deregisters this client and releases its socket.
     *
     * <p>Marks the handler inactive inside a {@code synchronized} block to
     * prevent any in-flight {@link #sendMessage} call from writing after the
     * socket has been closed.
     */
    private void cleanup()
    {
        // Synchronize so that the active flag flip is visible to all threads
        // before the socket is closed underneath them.
        synchronized (this) { active = false; }
        registry.removeClient(clientId);
        closeSocket();
    }

    /**
     * Closes the underlying socket, suppressing and logging any
     * {@link IOException} that arises during the close attempt.
     *
     * <p>Acquiring {@code this} monitor prevents a race between a concurrent
     * {@link #forceDisconnect()} call and the {@link #cleanup()} path where
     * both could attempt to close the socket simultaneously.
     */
    private synchronized void closeSocket()
    {
        // Skip if the socket was already closed by a prior code path.
        if (socket == null || socket.isClosed()) { return; }
        try {
            socket.close();
        } catch (IOException e) {
            // Failure to close is logged but not rethrown; the connection is
            // already considered terminated from the application's perspective.
            Logger.logClientHandlerError(
                "Failed to close socket for client " + clientId + ": " + e.getMessage()
            );
        }
    }
}
