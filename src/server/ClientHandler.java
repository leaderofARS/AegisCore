package server;

import core.Logger;
import player.Player;
import player.PlayerRegistry;
import protocol.CommandRouter;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the TCP socket lifecycle for a single connected player.
 *
 * <p>One instance is created per accepted {@link Socket}. It initialises the
 * player's I/O streams, creates the corresponding {@link Player} object, registers
 * it with {@link PlayerRegistry}, and enters a blocking read loop. Every input line
 * is forwarded to {@link CommandRouter#route(Player, String)} for parsing and dispatch.
 *
 * <p>Outbound delivery ({@link #sendMessage}) is serialised using {@code this} as the
 * monitor, preventing interleaved bytes when multiple broadcast threads write to the
 * same player simultaneously.
 *
 * <p>This class is thread-safe.
 */
public class ClientHandler implements Runnable {

    private final Socket          socket;
    private final String          sessionId;
    private final PlayerRegistry  playerRegistry;
    private final CommandRouter   commandRouter;

    private PrintWriter output;

    private volatile boolean     active          = false;
    private volatile boolean     forcedDisconnect = false;
    private final AtomicBoolean  evictionLogged  = new AtomicBoolean(false);

    private volatile Player player;

    /**
     * Constructs a handler for the given accepted socket.
     *
     * @param socket         open, connected client socket
     * @param playerRegistry shared registry for player registration
     * @param commandRouter  shared command dispatcher
     */
    public ClientHandler(Socket socket, PlayerRegistry playerRegistry, CommandRouter commandRouter) {
        this.socket         = socket;
        this.sessionId      = socket.getRemoteSocketAddress().toString();
        this.playerRegistry = playerRegistry;
        this.commandRouter  = commandRouter;
    }

    /** Returns the stable TCP session identifier (remote address string). */
    public String getSessionId() { return sessionId; }

    /** Returns {@code true} if the handler is active and accepting outbound messages. */
    public boolean isActive() { return active; }

    /**
     * Claims the eviction log right for this handler using a single atomic CAS.
     * Returns {@code true} exactly once across all concurrent callers.
     *
     * @return {@code true} if this call is the first to claim the log right
     */
    public boolean claimEvictionLog() { return evictionLogged.compareAndSet(false, true); }

    /**
     * Delivers a message to the client, serialised against concurrent broadcast calls.
     * Triggers {@link #forceDisconnect()} if the underlying stream signals a write error.
     * No-op when the handler is inactive.
     *
     * @param message text to deliver; a newline is appended automatically
     */
    public synchronized void sendMessage(String message) {
        if (!active) { return; }
        output.println(message);
        if (output.checkError()) {
            if (claimEvictionLog()) {
                Logger.logClientHandlerError("[DEAD SOCKET] Write failed for " + sessionId + " — forcing disconnect.");
            }
            forceDisconnect();
        }
    }

    /**
     * Forcibly closes this player's connection. Sets {@link #forcedDisconnect} before
     * closing the socket so the read loop logs the correct disconnect reason.
     * Idempotent — subsequent calls after the first are no-ops.
     */
    public synchronized void forceDisconnect() {
        if (!active) { return; }
        forcedDisconnect = true;
        active = false;
        closeSocket();
    }

    /**
     * Read loop for this player's dedicated thread.
     *
     * <p>Initialises streams, creates and registers a {@link Player}, then blocks on
     * {@link BufferedReader#readLine()} until the client disconnects or an error occurs.
     * Every received line is forwarded to {@link CommandRouter#route}. Cleanup runs
     * unconditionally in the {@code finally} block.
     */
    @Override
    public void run() {
        try {
            output = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            synchronized (this) { active = true; }

            player = new Player(sessionId, this);
            playerRegistry.register(player);

            sendMessage("[SERVER] Connected to AegisCore Game Lobby Server.");
            sendMessage("[SERVER] Set your name to begin:  NAME <username>");

            String line;
            while ((line = input.readLine()) != null) {
                commandRouter.route(player, line);
                if (!active) { break; }
            }

            if (forcedDisconnect) {
                Logger.logClientHandler("Player " + sessionId + " disconnected (forced).");
            } else {
                Logger.logClientHandler("Player " + sessionId + " disconnected cleanly.");
            }

        } catch (IOException e) {
            String msg = e.getMessage();
            if (msg != null && msg.toLowerCase().contains("connection reset")) {
                Logger.logClientHandler("Player " + sessionId + " closed connection abruptly.");
            } else {
                Logger.logClientHandlerError("I/O error for " + sessionId + ": " + msg);
            }
        } catch (Throwable t) {
            Logger.logClientHandlerError("Unexpected crash for " + sessionId + ": " + t.getMessage());
        } finally {
            cleanup();
        }
    }

    private void cleanup() {
        synchronized (this) { active = false; }
        if (player != null) {
            commandRouter.handleDisconnect(player);
            playerRegistry.deregister(sessionId);
        }
        closeSocket();
    }

    private synchronized void closeSocket() {
        if (socket == null || socket.isClosed()) { return; }
        try { socket.close(); }
        catch (IOException e) {
            Logger.logClientHandlerError("Failed to close socket for " + sessionId + ": " + e.getMessage());
        }
    }
}
