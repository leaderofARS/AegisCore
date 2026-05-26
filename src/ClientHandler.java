import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the full lifecycle of a single connected client on a dedicated thread.
 *
 * <p>One instance is created per accepted {@link Socket}. It owns the client's read
 * loop and exposes {@link #sendMessage(String)} for concurrent broadcast calls from
 * peer threads. All output to a given client is serialized through the
 * {@code synchronized} {@link #sendMessage(String)} method, which uses {@code this}
 * as its monitor — writes to distinct clients therefore proceed in parallel.
 *
 * <p>A single {@link java.io.PrintWriter} is stored as a field and shared by both
 * the read loop and broadcast callers, ensuring that only one wrapper ever touches
 * the underlying {@link java.io.OutputStream} at a time.
 *
 * <p>This class is thread-safe.
 */
public class ClientHandler implements Runnable
{
    private final SharedClientRegistry registry;
    private final Socket socket;
    private final String clientId;
    private PrintWriter  output;

    /**
     * {@code true} while the handler is ready to accept outbound messages.
     * Written inside {@code synchronized} blocks for check-then-act atomicity;
     * declared {@code volatile} so unsynchronized readers observe the latest value.
     */
    private volatile boolean active = false;

    /**
     * Guarantees that the dead-client eviction log line is emitted at most once,
     * even when multiple broadcast threads concurrently discover the same inactive handler.
     * The first thread to win the CAS from {@code false → true} logs; all others skip.
     */
    private final AtomicBoolean evictionLogged = new AtomicBoolean(false);

    /**
     * Set to {@code true} by {@link #forceDisconnect()} before the socket is closed,
     * allowing {@link #run()} to distinguish a forced teardown from a client-initiated
     * clean exit when selecting the disconnect log message.
     */
    private volatile boolean forcedDisconnect = false;

    /**
     * Constructs a handler for the given accepted socket.
     *
     * @param socket   open, connected client socket
     * @param registry shared registry used to register, deregister, and broadcast
     */
    public ClientHandler(Socket socket, SharedClientRegistry registry)
    {
        this.socket   = socket;
        this.registry = registry;
        this.clientId = socket.getRemoteSocketAddress().toString();
    }

    /** Returns the stable remote-address identifier for this client. */
    public String getClientId() { return clientId; }

    /** Returns {@code true} if the handler is active and accepting outbound messages. */
    public boolean isActive() { return active; }

    /**
     * Claims the right to log this handler's dead-client eviction, returning {@code true}
     * exactly once across all concurrent callers. Implemented as a single atomic CAS.
     *
     * @return {@code true} if this call is the first to claim the log right
     */
    public boolean claimEvictionLog()
    {
        return evictionLogged.compareAndSet(false, true);
    }

    /**
     * Delivers a message to this client, serialized against concurrent broadcast calls.
     * If {@link java.io.PrintWriter#checkError()} reports a broken stream after the write,
     * the handler tears itself down via {@link #forceDisconnect()}.
     * This method is a no-op when the handler is inactive.
     *
     * @param message text to deliver; a newline is appended automatically
     */
    public synchronized void sendMessage(String message)
    {
        if (!active) { return; }
        output.println(message);
        if (output.checkError()) {
            if (claimEvictionLog()) {
                Logger.logClientHandlerError(
                    "[DEAD CLIENT] Write failed for " + clientId + " — forcing disconnect."
                );
            }
            forceDisconnect();
        }
    }

    /**
     * Forcibly closes this client's connection.
     * Sets {@link #forcedDisconnect} before closing the socket so {@link #run()} can
     * log the correct disconnect reason. Idempotent — subsequent calls are no-ops.
     */
    synchronized void forceDisconnect()
    {
        if (!active) { return; }
        forcedDisconnect = true;
        active = false;
        closeSocket();
    }

    /**
     * Read loop for this client's dedicated thread.
     *
     * <p>Initialises the output stream, registers with the registry, then blocks on
     * {@link java.io.BufferedReader#readLine()} until the client disconnects, sends
     * {@code "exit"}, or an I/O error occurs. Each inbound line is acknowledged and
     * broadcast to all other clients. Cleanup runs unconditionally in the
     * {@code finally} block.
     */
    @Override
    public void run()
    {
        try {
            output = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            synchronized (this) { active = true; }

            registry.addClient(clientId, this);
            sendMessage("[SERVER] Connected. Welcome to AegisCore server!");

            String message;
            while ((message = input.readLine()) != null)
            {
                Logger.logClientHandler("Client " + clientId + " says: " + message);
                sendMessage("[ACK] Received: " + message);
                registry.BroadcastMessage("[" + clientId + "]: " + message);

                if (message.equalsIgnoreCase("exit")) {
                    sendMessage("[SERVER] Goodbye. Connection closing.");
                    break;
                }
            }

            if (forcedDisconnect) {
                Logger.logClientHandler("Client " + clientId + " disconnected (forced).");
            } else {
                Logger.logClientHandler("Client " + clientId + " disconnected cleanly.");
            }

        } catch (IOException e) {
            Logger.logClientHandlerError("I/O error for client " + clientId + ": " + e.getMessage());
        } catch (Throwable t) {
            Logger.logClientHandlerError("Unexpected crash for client " + clientId + ": " + t.getMessage());
        } finally {
            cleanup();
        }
    }

    private void cleanup()
    {
        synchronized (this) { active = false; }
        registry.removeClient(clientId);
        closeSocket();
    }

    private synchronized void closeSocket()
    {
        if (socket == null || socket.isClosed()) { return; }
        try {
            socket.close();
        } catch (IOException e) {
            Logger.logClientHandlerError("Failed to close socket for " + clientId + ": " + e.getMessage());
        }
    }
}
