import java.io.*;
import java.net.*;

/**
 * ClientHandler — Level 2.4: Synchronization Hardening
 *
 * Handles a single connected client on its own dedicated thread.
 * Implements thread-safe output stream serialization via fine-grained locking.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THREADING MODEL
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   One ClientHandler instance exists per connected client.
 *   It is run() by a dedicated thread (the "client thread").
 *
 *   However, OTHER client threads may call sendMessage() on THIS handler
 *   at any time — that is exactly what BroadcastMessage() does.
 *
 *   So at any moment, for a single ClientHandler:
 *     • Thread A  = this client's own thread (runs the read loop in run())
 *     • Thread B  = some OTHER client's thread (calls sendMessage() via broadcast)
 *     • Thread C  = yet another client's thread (also calling sendMessage())
 *
 *   All three may attempt to write to this client's OutputStream concurrently.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THE RACE CONDITION (what we are fixing)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   Before Level 2.4, sendMessage() created a NEW PrintWriter on every call:
 *
 *       public synchronized void sendMessage(String msg) {
 *           PrintWriter out = new PrintWriter(socket.getOutputStream(), true); // BUG
 *           out.println(msg);
 *       }
 *
 *   And run() had its OWN separate local PrintWriter for the same stream:
 *
 *       PrintWriter output = new PrintWriter(socket.getOutputStream(), true); // ALSO BUG
 *       output.println("[INFO] Connected!");  // ← NOT guarded by any lock
 *
 *   Two separate PrintWriter objects → both wrap the SAME OutputStream.
 *   Even though sendMessage() is synchronized, run()'s local writes are NOT.
 *   Result: two threads write to the same byte stream at the same time.
 *   Byte sequences interleave → client sees garbled data:
 *
 *       [Ali[Bob]]: hello     ← two messages fused into one corrupted line
 *
 *   This is a classic concurrent output contention bug.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THE FIX — SINGLE FIELD + SYNCHRONIZED CRITICAL SECTION
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   Solution: one PrintWriter stored as a field. ALL writes — from this thread
 *   AND from all foreign broadcast threads — go through the SAME synchronized
 *   sendMessage() method.
 *
 *   synchronized on "this" means: only one thread at a time can enter
 *   sendMessage() for THIS specific ClientHandler instance.
 *
 *   Critical section = only the output.println() line.
 *   Nothing else needs protection — that is fine-grained locking.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * WHY THIS IS FINE-GRAINED, NOT OVER-SYNCHRONIZED
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   Lock scope = THIS instance only.
 *
 *   Thread A writing to Client X holds Client X's lock.
 *   Thread B writing to Client Y holds Client Y's lock.
 *   → They do NOT block each other. Parallel writes across different clients
 *     proceed simultaneously — only writes to THE SAME client are serialized.
 *
 *   This is the maximum safe throughput achievable with per-stream locking.
 *   A global lock (synchronized on the registry) would force ALL writes to
 *   ALL clients to queue behind each other — throughput collapse.
 */
public class ClientHandler implements Runnable
{
    // ─────────────────────────────────────────────────────────────────────────
    // FIELDS
    // ─────────────────────────────────────────────────────────────────────────

    /** The registry this handler is registered with. Used for broadcast and cleanup. */
    private final SharedClientRegistry registry;

    /** The TCP socket for this specific client connection. */
    private final Socket socket;

    /**
     * Stable identifier for this client: remote IP + ephemeral port.
     * Example: "/127.0.0.1:54321"
     */
    private final String clientId;

    /**
     * THE SINGLE SHARED OUTPUT WRITER — the core of the Level 2.4 fix.
     *
     * There is exactly ONE PrintWriter per ClientHandler, wrapping the
     * socket's OutputStream. All writes from all threads flow through
     * sendMessage(), which synchronizes on "this" before touching this field.
     *
     * NOT final because it cannot be assigned in the constructor:
     * socket.getOutputStream() throws a checked IOException, which cannot
     * propagate from a constructor. It is initialized at the start of run().
     *
     * volatile is not sufficient here — we need atomicity of the println()
     * call itself, not just visibility of the reference. synchronized provides
     * both: mutual exclusion AND memory visibility.
     */
    private PrintWriter output;


    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCTOR
    // ─────────────────────────────────────────────────────────────────────────

    public ClientHandler(Socket socket, SharedClientRegistry registry)
    {
        this.socket   = socket;
        this.registry = registry;

        // Derive a stable, unique client ID from the remote socket address.
        // This includes both IP and ephemeral port — guaranteed unique per session.
        this.clientId = socket.getRemoteSocketAddress().toString();
    }

    public String getClientId()
    {
        return clientId;
    }


    // ─────────────────────────────────────────────────────────────────────────
    // CRITICAL SECTION — THE SYNCHRONIZED OUTPUT GATE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a message to this client's socket stream.
     *
     * SYNCHRONIZATION:
     *   The "synchronized" keyword on this method acquires the intrinsic monitor
     *   (lock) of THIS ClientHandler instance before entering.
     *
     *   Any thread that calls sendMessage() on the same handler while another
     *   thread is already inside it will BLOCK until the lock is released.
     *   This serializes all writes to this client's stream — preventing interleave.
     *
     * CRITICAL SECTION:
     *   Only the output.println(message) call accesses shared mutable state
     *   (the socket OutputStream). That is the only line that needs protection.
     *
     *   The guard checks (output == null, socket.isClosed()) are read-only and
     *   safe to include inside the lock. They add negligible overhead.
     *
     * CALLED BY:
     *   • This client's own thread (from run()) — for welcome message and ACKs.
     *   • Foreign broadcast threads — for messages from other clients.
     *   Multiple callers, ONE gate. That is the contract.
     *
     * @param message The message string to send. A newline is appended by println().
     */
    public synchronized void sendMessage(String message)
    {
        // Guard: if the stream was never initialized or the socket was closed,
        // silently discard the write. This avoids NullPointerException and
        // writing to a closed channel during shutdown.
        if (output == null || socket.isClosed()) {
            return;
        }

        // ── CRITICAL SECTION START ──────────────────────────────────────────
        // Only ONE thread executes this line at a time for this specific handler.
        // The JVM enforces this via the intrinsic lock acquired above.
        output.println(message);
        // ── CRITICAL SECTION END ────────────────────────────────────────────

        // PrintWriter swallows IOExceptions internally and sets an error flag.
        // checkError() surfaces that flag so we can detect broken connections.
        if (output.checkError()) {
            Logger.logClientHandlerError(
                "Stream write error detected for client " + clientId +
                " — connection may be broken."
            );
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // CLIENT LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Entry point for this client's dedicated thread.
     *
     * DESIGN:
     *   Even though only THIS thread runs run(), all writes to the client still
     *   go through sendMessage(). This is intentional — it keeps the invariant:
     *   "the only path to the output stream is through the synchronized gate."
     *   If we bypassed sendMessage() here with a direct output.println(), we would
     *   reintroduce the race condition we just fixed.
     */
    @Override
    public void run()
    {
        try {
            // ── INITIALIZE THE SHARED OUTPUT WRITER ─────────────────────────
            // Done here — not in the constructor — because getOutputStream()
            // throws a checked IOException, which cannot leave a constructor.
            // auto-flush = true: flushes the buffer after every println() call.
            // Without auto-flush, messages would sit in the buffer indefinitely.
            output = new PrintWriter(socket.getOutputStream(), true);

            // Input reader: reads newline-delimited text from the client.
            // BufferedReader wraps the raw InputStream with an efficient buffer.
            BufferedReader input = new BufferedReader(
                new InputStreamReader(socket.getInputStream())
            );

            // ── WELCOME MESSAGE ─────────────────────────────────────────────
            // Sent through the synchronized gate — same path as broadcast messages.
            // Consistent discipline: NO direct output.println() calls anywhere.
            sendMessage("[SERVER] Connected. Welcome to AegisCore server!");

            // ── READ LOOP ───────────────────────────────────────────────────
            // readLine() BLOCKS this thread until:
            //   a) the client sends a newline-terminated message, OR
            //   b) the client closes the connection (returns null), OR
            //   c) a network error occurs (throws IOException).
            String message;
            while ((message = input.readLine()) != null)
            {
                Logger.logClientHandler("Client " + clientId + " says: " + message);

                // Send acknowledgement back to the SENDING client.
                // Goes through the synchronized gate.
                sendMessage("[ACK] Received: " + message);

                // Distribute to ALL other connected clients via the registry.
                // BroadcastMessage() iterates all handlers and calls each one's
                // synchronized sendMessage() — acquiring each client's lock
                // independently. No global lock held during the loop.
                registry.BroadcastMessage("[" + clientId + "]: " + message);

                // Honour the exit command — break the read loop cleanly.
                if (message.equalsIgnoreCase("exit")) {
                    sendMessage("[SERVER] Goodbye. Connection closing.");
                    break;
                }
            }

            Logger.logClientHandler("Client " + clientId + " disconnected cleanly.");

        } catch (IOException e) {
            // IO error: network reset, client crash, pipe broken, etc.
            Logger.logClientHandlerError(
                "I/O error for client " + clientId + ": " + e.getMessage()
            );
        } finally {
            // finally ALWAYS executes — even if an exception was thrown above.
            // This guarantees the client is unregistered and the socket is closed,
            // preventing resource leaks (zombie threads, port exhaustion).
            cleanup();
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // CLEANUP
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Unregisters this client from the global registry and closes the socket.
     *
     * ORDER MATTERS:
     *   removeClient() first — so no new broadcast messages are dispatched to
     *   this handler after we begin teardown. If we closed the socket first,
     *   a concurrent broadcast could call sendMessage() on a closed stream
     *   before the handler is removed from the registry.
     */
    private void cleanup()
    {
        // Step 1: remove from registry — no new messages will be routed here.
        registry.removeClient(clientId);

        // Step 2: close the TCP socket — releases OS port and network resources.
        closeSocket();
    }

    /**
     * Closes the TCP socket safely.
     * Guards against null reference and double-close (both are no-ops here).
     */
    private void closeSocket()
    {
        if (socket == null || socket.isClosed()) {
            return; // Already closed or never opened — nothing to do.
        }

        try {
            socket.close();
        } catch (IOException e) {
            Logger.logClientHandlerError(
                "Failed to close socket for client " + clientId + ": " + e.getMessage()
            );
        }
    }
}
