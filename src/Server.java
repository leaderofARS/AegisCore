import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Entry point and lifecycle manager for the AegisCore multithreaded TCP server.
 *
 * <p>Binds a {@link ServerSocket} to {@link #PORT} and enters an infinite accept-loop,
 * spawning one dedicated {@link Thread} per accepted client connection. Each thread runs a
 * {@link ClientHandler} that self-registers with the {@link SharedClientRegistry} only after
 * its output stream is fully initialised, preventing broadcast races during handler startup.
 *
 * <p><b>Graceful shutdown</b> proceeds in three ordered phases when the JVM shutdown hook fires
 * (e.g. on SIGTERM or Ctrl-C):
 * <ol>
 *   <li>Close the {@link ServerSocket} — stops accepting new connections.</li>
 *   <li>Call {@link SharedClientRegistry#shutdownAllClients()} — sends each connected client a
 *       goodbye message and calls {@link ClientHandler#forceDisconnect()}, unblocking their
 *       read loops.</li>
 *   <li>Join every tracked client thread against a shared {@value #SHUTDOWN_TIMEOUT_MS} ms
 *       deadline, then log a final summary.</li>
 * </ol>
 *
 * <p>This class is a non-instantiable static utility.
 */
public class Server {

    private static final int  PORT                = 5000;
    private static final long SHUTDOWN_TIMEOUT_MS = 5_000;

    private static ServerSocket      serverSocket;
    private static final List<Thread> clientThreads = new CopyOnWriteArrayList<>();

    /**
     * Starts the server, registers a JVM shutdown hook, and enters the accept-loop.
     *
     * @param args command-line arguments; currently unused
     */
    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(Server::shutdown, "ShutdownHook"));

        try {
            serverSocket = new ServerSocket(PORT, 500);
            Logger.logServer("Server is listening on port " + PORT);

            SharedClientRegistry registry = SharedClientRegistry.getInstance();

            while (true) {
                Logger.logServer("Waiting for clients to connect...");
                Socket clientSocket = serverSocket.accept();
                Logger.logServer("New client connected: " + clientSocket.getInetAddress().getHostAddress());

                ClientHandler clientHandler = new ClientHandler(clientSocket, registry);
                Thread clientThread = new Thread(clientHandler, "ClientHandler-" + clientSocket.getPort());
                clientThreads.add(clientThread);
                clientThread.start();
            }

        } catch (IOException e) {
            if (serverSocket != null && serverSocket.isClosed()) {
                Logger.logServer("Server stopped accepting connections.");
            } else {
                Logger.logServerError("Server error: " + e.getMessage());
            }
        } catch (Throwable t) {
            Logger.logServerError("Unexpected server crash: " + t.getMessage());
        }
    }

    /**
     * Shuts down the server in three phases: stop accepting, signal all clients, await threads.
     * Idempotent — safe to call multiple times.
     */
    public static void shutdown() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
                Logger.logServer("ServerSocket closed. No new connections will be accepted.");
            } catch (IOException e) {
                Logger.logServerError("Failed to close ServerSocket: " + e.getMessage());
            }
        }

        SharedClientRegistry.getInstance().shutdownAllClients();

        int total    = clientThreads.size();
        int finished = 0;
        int abandoned = 0;

        Logger.logServer("Shutdown: waiting for " + total + " client thread(s) (timeout: " + SHUTDOWN_TIMEOUT_MS + "ms)...");

        long deadline = System.currentTimeMillis() + SHUTDOWN_TIMEOUT_MS;

        for (Thread t : clientThreads) {
            if (!t.isAlive()) { finished++; continue; }

            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) { abandoned++; continue; }

            try {
                t.join(remaining);
                if (!t.isAlive()) {
                    finished++;
                } else {
                    abandoned++;
                    Logger.logServerError("Shutdown: thread " + t.getName() + " did not finish — abandoned.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Logger.logServerError("Shutdown interrupted while waiting for " + t.getName() + ".");
                break;
            }
        }

        Logger.logServer(
            "Shutdown complete — total: " + total +
            " | finished: " + finished +
            " | abandoned: " + abandoned
        );
    }
}