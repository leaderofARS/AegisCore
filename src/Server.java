import java.io.*;
import java.net.*;

// -------------------------------------------------------------------------
// SERVER — AegisCore TCP Chat Server Entry Point
// -------------------------------------------------------------------------

/**
 * Entry point and lifecycle manager for the AegisCore multithreaded TCP chat server.
 *
 * <p>This class binds a {@link ServerSocket} to {@link #PORT} and enters an infinite
 * accept-loop, spawning one dedicated {@link Thread} per accepted client connection.
 * Each thread executes a {@link ClientHandler} that owns the per-client I/O and
 * registers itself into the {@link SharedClientRegistry} only after its output stream
 * is fully initialised — eliminating the lifecycle-ordering race that would otherwise
 * allow a broadcaster to discover a handler before it is ready to receive messages.
 *
 * <p><b>Threading model:</b> The main thread runs the blocking accept-loop exclusively.
 * Every accepted connection is handed off to a freshly created {@code ClientHandler-<port>}
 * thread immediately, so the main thread is never blocked on per-client I/O.
 * {@link #shutdown()} is called from a JVM shutdown hook and is therefore invoked on
 * a separate hook thread; it is safe to call concurrently with the accept-loop because
 * closing the {@link ServerSocket} causes {@link ServerSocket#accept()} to throw
 * {@link IOException}, which terminates the loop cleanly.
 *
 * <p><b>Design pattern:</b> The class is a pure static utility / application entry point.
 * It is never instantiated; all state is held in static fields guarded by the sequential
 * startup contract (only {@code main} writes {@link #serverSocket}).
 */
public class Server {

    // -------------------------------------------------------------------------
    // CONSTANTS
    // -------------------------------------------------------------------------

    /**
     * The TCP port on which the server listens for incoming client connections.
     *
     * <p>Port 5000 is chosen to avoid the privileged range (&lt;1024) and to stay
     * clear of commonly used ephemeral ports, making it predictable for all clients
     * in the AegisCore ecosystem.
     */
    private static final int PORT = 5000;

    // -------------------------------------------------------------------------
    // FIELDS
    // -------------------------------------------------------------------------

    /**
     * The single server-side socket that accepts all inbound TCP connections.
     *
     * <p>Written once by {@link #main(String[])} before the accept-loop starts and
     * subsequently read by {@link #shutdown()}. Because the write happens-before any
     * read (main thread initialises it before the shutdown hook could ever fire),
     * no additional synchronisation is required.
     */
    private static ServerSocket serverSocket;

    // -------------------------------------------------------------------------
    // ENTRY POINT
    // -------------------------------------------------------------------------

    /**
     * Starts the AegisCore server, registers a graceful-shutdown hook, and enters
     * the client-accept loop.
     *
     * <p>Execution proceeds as follows:
     * <ol>
     *   <li>A JVM shutdown hook is registered so that {@link #shutdown()} is called
     *       automatically when the process receives SIGTERM, SIGINT, or
     *       {@code System.exit()}.</li>
     *   <li>{@link #serverSocket} is bound to {@link #PORT}.</li>
     *   <li>The method loops forever, blocking on {@link ServerSocket#accept()} until
     *       a client connects.</li>
     *   <li>For each accepted connection a {@link ClientHandler} is created and started
     *       on a named thread. {@code registry.addClient()} is intentionally <em>not</em>
     *       called here — the handler registers itself once its output stream is ready,
     *       preventing broadcast attempts on a partially-initialised handler.</li>
     * </ol>
     *
     * <p>The accept-loop exits when {@link #shutdown()} closes {@link #serverSocket},
     * causing {@link ServerSocket#accept()} to throw {@link IOException}. That exception
     * is caught and distinguished from genuine errors by inspecting
     * {@link ServerSocket#isClosed()}.
     *
     * @param args command-line arguments; currently unused.
     */
    public static void main(String[] args) {
        // Register a JVM shutdown hook so that the ServerSocket is closed cleanly
        // when the process terminates (e.g. Ctrl-C, SIGTERM, or System.exit()).
        // The hook runs on its own thread; shutdown() is written to be idempotent.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Server.shutdown();
        }));

        try {
            // Bind to the port with a connection backlog of 500 to ensure high-concurrency
            // client connect bursts do not cause OS-level connection-refused errors.
            serverSocket = new ServerSocket(PORT, 500);
            Logger.logServer("Server is listening on port " + PORT);

            // Obtain the singleton registry that tracks all live client handlers.
            // The registry is shared with every ClientHandler spawned below.
            SharedClientRegistry registry = SharedClientRegistry.getInstance();

            while (true) {
                Logger.logServer("Waiting for clients to connect...");

                // Blocks this thread until a client completes the TCP handshake.
                // Throws IOException (breaking the loop) when serverSocket is closed
                // by the shutdown hook.
                Socket clientSocket = serverSocket.accept();

                Logger.logServer(
                    "New client connected: " + clientSocket.getInetAddress().getHostAddress()
                );

                // Construct the handler before starting the thread so that any
                // constructor-level failure is caught here on the accept thread,
                // keeping error handling centralised and the accept-loop intact.
                //
                // IMPORTANT: The handler registers itself with the registry inside
                // ClientHandler.run(), AFTER its output stream is initialised.
                // Registering here (before run()) would expose the handler to
                // broadcasters before it is ready to receive data, creating a
                // lifecycle-ordering race condition.
                ClientHandler clientHandler = new ClientHandler(clientSocket, registry);

                // NOTE: registry.addClient() is NOT called here.
                // It is called inside ClientHandler.run() once the handler
                // has fully initialized its output stream and is ready.
                Thread clientThread = new Thread(clientHandler);

                // Assign a human-readable name so that thread dumps identify which
                // port each handler is servicing without requiring extra tooling.
                clientThread.setName("ClientHandler-" + clientSocket.getPort());
                clientThread.start();
            }

        } catch (IOException e) {
            if (serverSocket != null && serverSocket.isClosed()) {
                // Expected path: shutdown() closed the socket to break the accept-loop.
                // This is not an error; log at informational level and exit cleanly.
                Logger.logServer("Server stopped accepting connections.");
            } else {
                // Unexpected I/O failure while the socket should still be open.
                Logger.logServerError("Server error occurred: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------------------------

    /**
     * Closes the {@link ServerSocket}, causing the accept-loop in {@link #main} to
     * terminate gracefully.
     *
     * <p>This method is designed to be called from the JVM shutdown hook registered
     * in {@link #main}, but it may safely be called from any thread. It is idempotent:
     * if {@link #serverSocket} is {@code null} or already closed the method returns
     * immediately without logging or throwing.
     *
     * <p>Closing the {@link ServerSocket} unblocks the {@link ServerSocket#accept()}
     * call in the main thread by raising an {@link IOException}, which is the standard
     * Java idiom for interrupting a blocking server loop without requiring a sentinel
     * connection or volatile flags.
     */
    public static void shutdown() {
        // Guard against a null socket (shutdown called before main initialised it)
        // or a double-close (shutdown hook firing after the socket was already closed).
        if (serverSocket == null || serverSocket.isClosed()) {
            return;
        }

        try {
            serverSocket.close();
            Logger.logServer("ServerSocket closed.");
        } catch (IOException e) {
            Logger.logServerError("Failed to close ServerSocket: " + e.getMessage());
        }
    }
}