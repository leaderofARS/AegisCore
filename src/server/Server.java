package server;

import core.Logger;
import matchmaking.MatchConfig;
import matchmaking.MatchmakingQueue;
import player.PlayerRegistry;
import protocol.CommandRouter;
import room.RoomRegistry;
import admin.ServerConfig;
import security.ConnectionGuard;
import security.BanList;
import network.ClientThreadPool;
import network.HeartbeatManager;
import core.MetricsServer;

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

    private static ServerSocket      serverSocket;
    private static MatchmakingQueue  matchmakingQueue;
    private static Thread            matchmakingThread;
    private static MetricsServer     metricsServer;

    /**
     * Starts the server: initialises all subsystems, registers a shutdown hook,
     * and enters the accept loop.
     *
     * @param args command-line arguments; parses --port
     */
    public static void main(String[] args) {
        // Parse --port command line arg to override default config
        for (int i = 0; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                System.setProperty("aegiscore.port", args[i + 1]);
            }
        }

        Runtime.getRuntime().addShutdownHook(new Thread(Server::shutdown, "ShutdownHook"));

        ServerConfig config = ServerConfig.getInstance();
        Logger.logServer("Loaded server configuration: " + config);

        PlayerRegistry playerRegistry = PlayerRegistry.getInstance();
        RoomRegistry   roomRegistry   = RoomRegistry.getInstance();
        MatchConfig    matchConfig    = MatchConfig.defaultConfig(); // matchmaking config
        
        matchmakingQueue = new MatchmakingQueue(matchConfig, roomRegistry);
        CommandRouter commandRouter  = new CommandRouter(playerRegistry, roomRegistry, matchmakingQueue);

        // Start matchmaking daemon thread
        matchmakingThread = new Thread(matchmakingQueue, "MatchmakingQueue");
        matchmakingThread.setDaemon(true);
        matchmakingThread.start();

        // Start heartbeat manager
        HeartbeatManager.getInstance().start();

        // Initialize and start metrics server if enabled
        if (config.isMetricsEnabled()) {
            core.MetricsCollector.getInstance().initialize(playerRegistry, roomRegistry, matchmakingQueue);
            try {
                metricsServer = MetricsServer.start(config.getMetricsPort(), core.MetricsCollector.getInstance());
            } catch (IOException e) {
                Logger.logServerError("Failed to start MetricsServer: " + e.getMessage());
            }
        }

        int port = config.getPort();
        try {
            serverSocket = new ServerSocket(port, 500);
            Logger.logServer("╔══════════════════════════════════════════════╗");
            Logger.logServer("║   AegisCore Game Lobby Server                ║");
            Logger.logServer("║   Listening on port " + port + "                      ║");
            Logger.logServer("╚══════════════════════════════════════════════╝");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                String ip = clientSocket.getInetAddress().getHostAddress();

                // Check BanList before accepting connection
                if (BanList.getInstance().isIpBanned(ip)) {
                    Logger.logServer("Rejected banned connection from: " + ip);
                    clientSocket.close();
                    continue;
                }

                // Check ConnectionGuard limit
                if (!ConnectionGuard.getInstance().allowConnection(ip)) {
                    Logger.logServer("Connection guard rejected: " + ip + " (too many connections)");
                    clientSocket.close();
                    continue;
                }

                Logger.logServer("Player connected: " + clientSocket.getInetAddress().getHostAddress());

                // Create handler
                ClientHandler handler = new ClientHandler(clientSocket, playerRegistry, commandRouter) {
                    @Override
                    public void run() {
                        try {
                            super.run();
                        } finally {
                            // Release IP connection slot
                            ConnectionGuard.getInstance().releaseConnection(ip);
                        }
                    }
                };

                // Submit connection handling to virtual thread executor
                ClientThreadPool.getInstance().submit(handler, "Player-" + clientSocket.getPort());
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

        HeartbeatManager.getInstance().stop();

        if (metricsServer != null) {
            metricsServer.stop();
        }

        // Shut down client thread pool
        int timeoutSec = ServerConfig.getInstance().getHeartbeatTimeoutSeconds();
        ClientThreadPool.getInstance().shutdown(timeoutSec * 1000L);

        Logger.logServer("Shutdown complete.");
    }
}
