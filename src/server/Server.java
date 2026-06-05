package server;

import core.Logger;
import matchmaking.MatchConfig;
import matchmaking.MatchmakingQueue;
import player.PlayerRegistry;
import protocol.CommandRouter;
import room.RoomRegistry;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Entry point and lifecycle manager for the AegisCore Game Lobby Server.
 *
 * <p>Binds a {@link ServerSocket} to {@link #PORT}, starts the matchmaking daemon,
 * then enters an accept loop — spawning one {@link ClientHandler} thread per connection.
 *
 * <p>Graceful shutdown proceeds in three phases when the JVM shutdown hook fires:
 * <ol>
 *   <li>Close the {@link ServerSocket} — stops accepting new connections.</li>
 *   <li>Broadcast a shutdown notice and stop the matchmaking daemon.</li>
 *   <li>Join all tracked client threads against a {@value #SHUTDOWN_TIMEOUT_MS}ms deadline.</li>
 * </ol>
 */
public class Server {

    private static final int  PORT                = 5000;
    private static final long SHUTDOWN_TIMEOUT_MS = 5_000;

    private static ServerSocket      serverSocket;
    private static MatchmakingQueue  matchmakingQueue;
    private static Thread            matchmakingThread;

    private static final List<Thread> clientThreads = new CopyOnWriteArrayList<>();

    /**
     * Starts the server: initialises all subsystems, registers a shutdown hook,
     * and enters the accept loop.
     *
     * @param args command-line arguments; not used
     */
    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(Server::shutdown, "ShutdownHook"));

        PlayerRegistry playerRegistry = PlayerRegistry.getInstance();
        RoomRegistry   roomRegistry   = RoomRegistry.getInstance();
        MatchConfig    matchConfig    = MatchConfig.defaultConfig();
        matchmakingQueue = new MatchmakingQueue(matchConfig, roomRegistry);
        CommandRouter commandRouter  = new CommandRouter(playerRegistry, roomRegistry, matchmakingQueue);

        matchmakingThread = new Thread(matchmakingQueue, "MatchmakingQueue");
        matchmakingThread.setDaemon(true);
        matchmakingThread.start();

        try {
            serverSocket = new ServerSocket(PORT, 500);
            Logger.logServer("╔══════════════════════════════════════════════╗");
            Logger.logServer("║   AegisCore Game Lobby Server                ║");
            Logger.logServer("║   Listening on port " + PORT + "                      ║");
            Logger.logServer("╚══════════════════════════════════════════════╝");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                Logger.logServer("Player connected: " + clientSocket.getInetAddress().getHostAddress());

                ClientHandler handler = new ClientHandler(clientSocket, playerRegistry, commandRouter);
                Thread clientThread  = new Thread(handler, "Player-" + clientSocket.getPort());
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
     * Shuts down the server in three ordered phases. Idempotent.
     */
    public static void shutdown() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
                Logger.logServer("ServerSocket closed.");
            } catch (IOException e) {
                Logger.logServerError("Failed to close ServerSocket: " + e.getMessage());
            }
        }

        PlayerRegistry.getInstance().broadcastAll("[SERVER] Server is shutting down. Thank you for playing.");

        if (matchmakingQueue  != null) { matchmakingQueue.stop(); }
        if (matchmakingThread != null) { matchmakingThread.interrupt(); }

        int total = clientThreads.size(), finished = 0, abandoned = 0;
        Logger.logServer("Shutdown: waiting for " + total + " player thread(s) (timeout: " + SHUTDOWN_TIMEOUT_MS + "ms)...");

        long deadline = System.currentTimeMillis() + SHUTDOWN_TIMEOUT_MS;
        for (Thread t : clientThreads) {
            if (!t.isAlive()) { finished++; continue; }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) { abandoned++; continue; }
            try {
                t.join(remaining);
                if (!t.isAlive()) { finished++; } else { abandoned++; }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        Logger.logServer("Shutdown complete — total: " + total +
                         " | finished: " + finished + " | abandoned: " + abandoned);
    }
}
