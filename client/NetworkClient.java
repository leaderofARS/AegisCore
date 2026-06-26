import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the TCP connection between the AegisCore RPG client and the server.
 *
 * <h3>Responsibilities</h3>
 * <ol>
 *   <li><strong>Establish a connection</strong> — opens a {@link Socket} to the
 *       AegisCore server and spawns a dedicated background reader thread.</li>
 *   <li><strong>Join lobby sequence</strong> — after the server's greeting is
 *       received, automatically sends {@code NAME}, then attempts to
 *       {@code JOIN r-001}; if the room doesn't exist yet, it sends
 *       {@code CREATE AegisRPG 10} to create one, then {@code READY}.</li>
 *   <li><strong>Parse game messages</strong> — the reader thread interprets
 *       every {@code [GAME] PLAYER} and {@code [GAME] LEAVE} line from the
 *       server and updates the {@link GamePanel}'s {@code remotePlayers} map
 *       accordingly.</li>
 *   <li><strong>Broadcast position</strong> — {@link #sendGamePos} is called by
 *       the game loop every {@code NET_SYNC_INTERVAL} frames and only emits a
 *       packet when the position or direction has actually changed, keeping
 *       server traffic minimal.</li>
 * </ol>
 *
 * <h3>AegisCore protocol overview</h3>
 * <pre>
 *   Client → Server
 *     NAME &lt;displayName&gt;             register player name
 *     JOIN &lt;roomId&gt;                  join existing room
 *     CREATE &lt;name&gt; &lt;maxPlayers&gt;     create new room
 *     READY                          signal ready for game start
 *     GAMEPOS &lt;x&gt; &lt;y&gt; &lt;direction&gt;   position update (rate-limit exempt)
 *     QUIT                           clean disconnect
 *
 *   Server → Client  (game session messages)
 *     [GAME] SESSION_START            game session is live — begin PLAYING
 *     [GAME] PLAYER &lt;name&gt; &lt;x&gt; &lt;y&gt; &lt;dir&gt;   player position update
 *     [GAME] LEAVE &lt;name&gt;            player disconnected
 * </pre>
 *
 * <h3>Thread safety</h3>
 * {@link #send(String)} is {@code synchronized} so the game thread and the
 * join thread never interleave partial writes.  The {@link AtomicBoolean}
 * {@link #connected} provides a safe cross-thread stop signal.
 */
public class NetworkClient {

    // ── Protocol prefixes ─────────────────────────────────────────────────────
    private static final String GAME_PREFIX   = "[GAME]";
    private static final String CMD_PLAYER    = "PLAYER";
    private static final String CMD_LEAVE     = "LEAVE";
    private static final String DEFAULT_ROOM  = "AegisRPG";
    private static final String FIRST_ROOM_ID = "r-001";

    // ── Connection fields ─────────────────────────────────────────────────────
    private final String  serverAddress;
    private final int     serverPort;
    private final String  playerName;

    private Socket         socket;
    private PrintWriter    writer;
    private BufferedReader reader;

    /** {@code true} while the TCP socket is open and the reader is running. */
    private final AtomicBoolean connected = new AtomicBoolean(false);

    // ── Back-reference to the game panel ─────────────────────────────────────
    private final GamePanel gamePanel;

    // ── Dynamic game state ────────────────────────────────────────────────────
    private volatile String roomName = "—";

    // ── Position-change deduplication ─────────────────────────────────────────
    private int    lastSentX   = Integer.MIN_VALUE;
    private int    lastSentY   = Integer.MIN_VALUE;
    private String lastSentDir = "";

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param gamePanel     back-reference to update {@code remotePlayers}
     * @param playerName    the local player's chosen display name
     * @param serverAddress AegisCore server hostname or IP
     * @param serverPort    AegisCore server port (typically {@code 5000})
     */
    public NetworkClient(GamePanel gamePanel, String playerName,
                         String serverAddress, int serverPort) {
        this.gamePanel     = gamePanel;
        this.playerName    = playerName;
        this.serverAddress = serverAddress;
        this.serverPort    = serverPort;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Opens the TCP socket and starts the background reader thread.
     * Called once from the EDT before the game thread starts.
     */
    public void connect() {
        try {
            socket    = new Socket(serverAddress, serverPort);
            writer    = new PrintWriter(
                            new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())),
                            true /* auto-flush on println */);
            reader    = new BufferedReader(
                            new InputStreamReader(socket.getInputStream()));
            connected.set(true);

            // All network I/O happens in a single daemon background thread
            Thread readerThread = new Thread(this::readLoop, "AegisRPG-Net");
            readerThread.setDaemon(true);
            readerThread.start();

            System.out.println("[Net] Connected to " + serverAddress + ":" + serverPort);

        } catch (IOException e) {
            System.err.println("[Net] Connection failed: " + e.getMessage());
            connected.set(false);
        }
    }

    /** Sends {@code QUIT} and closes the socket. Safe to call from any thread. */
    public void disconnect() {
        if (!connected.getAndSet(false)) return;
        send("QUIT");
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    // ── Game-loop API ─────────────────────────────────────────────────────────

    /**
     * Sends a {@code GAMEPOS} position update to the server, but only if the
     * position or direction has changed since the last transmission — preventing
     * redundant network traffic when the player is standing still.
     *
     * @param x   world-space X in pixels
     * @param y   world-space Y in pixels
     * @param dir facing direction ({@code "UP"}, {@code "DOWN"}, etc.)
     */
    public void sendGamePos(int x, int y, String dir) {
        if (!connected.get()) return;
        if (x == lastSentX && y == lastSentY && dir.equals(lastSentDir)) return;
        send("GAMEPOS " + x + " " + y + " " + dir);
        lastSentX   = x;
        lastSentY   = y;
        lastSentDir = dir;
    }

    // ── Internal: reader loop (background thread) ─────────────────────────────

    /**
     * Reads lines from the server in a tight loop.  Handles the lobby handshake
     * inline before entering the steady-state game-message dispatch.
     *
     * <h4>Handshake sequence</h4>
     * <pre>
     *   1. Read greeting line 1  → "[SERVER] Connected to AegisCore..."
     *   2. Read greeting line 2  → "[SERVER] Set your name to begin..."
     *   3. Send: NAME &lt;playerName&gt;
     *   4. Read until "[SERVER] Welcome" or error.
     *   5. Try:  JOIN r-001
     *   6. Read response:
     *        "[ROOM] You joined..."   → send READY
     *        "[ERROR] Room not found" → send CREATE AegisRPG 10, then READY
     *   7. Enter steady-state game-message loop.
     * </pre>
     */
    private void readLoop() {
        try {
            // ── Step 1-2: drain server greeting ──────────────────────────────
            String g1 = reader.readLine();
            String g2 = reader.readLine();
            if (g1 == null || g2 == null) {
                System.err.println("[Net] Server closed connection during greeting.");
                return;
            }
            System.out.println("[Net] " + g1);
            System.out.println("[Net] " + g2);

            // ── Step 3: register name ─────────────────────────────────────────
            send("NAME " + playerName);

            // ── Step 4: wait for welcome ──────────────────────────────────────
            String line;
            boolean named = false;
            while ((line = reader.readLine()) != null) {
                System.out.println("[Server] " + line);
                if (line.contains("Welcome") || line.contains("welcome")) { named = true; break; }
                if (line.startsWith("[ERROR]")) {
                    System.err.println("[Net] Naming failed: " + line);
                    return; // name taken or banned
                }
            }
            if (!named) return;

            // ── Step 5: attempt to join the default room ──────────────────────
            send("JOIN " + FIRST_ROOM_ID);

            // ── Step 6: handle join/create response ───────────────────────────
            boolean inRoom = false;
            while ((line = reader.readLine()) != null) {
                System.out.println("[Server] " + line);

                if (line.startsWith("[ROOM] You joined") || line.startsWith("[ROOM] Room created")) {
                    // Extract room name from the line for the HUD
                    roomName = DEFAULT_ROOM;
                    inRoom   = true;
                    send("READY");
                    break;
                }
                if (line.startsWith("[ERROR] Room not found")) {
                    // Room r-001 doesn't exist yet — we are the first player
                    send("CREATE " + DEFAULT_ROOM + " 10");
                    // Read CREATE confirmation, then send READY
                    String createResp = reader.readLine();
                    if (createResp != null) {
                        System.out.println("[Server] " + createResp);
                        if (createResp.startsWith("[ROOM] Room created")) {
                            roomName = DEFAULT_ROOM;
                            inRoom   = true;
                            send("READY");
                        }
                    }
                    break;
                }
                // Other messages (broadcasts, etc.) — handle if game-related
                if (line.startsWith(GAME_PREFIX)) {
                    handleGameMessage(line.substring(GAME_PREFIX.length()).trim());
                }
            }
            if (!inRoom) { System.err.println("[Net] Could not join or create room."); return; }

            // ── Step 7: steady-state game message loop ────────────────────────
            while (connected.get() && (line = reader.readLine()) != null) {
                System.out.println("[Server] " + line);
                if (line.startsWith(GAME_PREFIX)) {
                    handleGameMessage(line.substring(GAME_PREFIX.length()).trim());
                }
            }

        } catch (IOException e) {
            if (connected.get()) {
                System.err.println("[Net] Connection lost: " + e.getMessage());
            }
        } finally {
            connected.set(false);
            System.out.println("[Net] Reader thread exiting.");
        }
    }

    // ── Game message dispatcher ───────────────────────────────────────────────

    /**
     * Dispatches a single {@code [GAME] ...} payload to the appropriate handler.
     *
     * @param payload the text that follows the {@code "[GAME] "} prefix
     */
    private void handleGameMessage(String payload) {

        // ── [GAME] SESSION_START ──────────────────────────────────────────────
        if ("SESSION_START".equals(payload)) {
            System.out.println("[Net] Game session is live!");
            return;
        }

        // ── [GAME] PLAYER <name> <x> <y> <direction> ─────────────────────────
        if (payload.startsWith(CMD_PLAYER + " ")) {
            String   body  = payload.substring(CMD_PLAYER.length() + 1);
            String[] parts = body.split("\\s+", 4);
            if (parts.length < 3) return;

            String name = parts[0];
            if (name.equals(playerName)) return; // ignore echo of our own position

            try {
                int    wx  = Integer.parseInt(parts[1]);
                int    wy  = Integer.parseInt(parts[2]);
                String dir = parts.length > 3 ? parts[3] : "IDLE";

                // Update or create the RemotePlayer entry (thread-safe via CHM)
                RemotePlayer rp = gamePanel.remotePlayers.computeIfAbsent(name, RemotePlayer::new);
                rp.worldX    = wx;
                rp.worldY    = wy;
                rp.direction = dir;

            } catch (NumberFormatException e) {
                System.err.println("[Net] Malformed PLAYER message: " + payload);
            }
            return;
        }

        // ── [GAME] LEAVE <name> ───────────────────────────────────────────────
        if (payload.startsWith(CMD_LEAVE + " ")) {
            String name = payload.substring(CMD_LEAVE.length() + 1).trim();
            gamePanel.remotePlayers.remove(name);
            System.out.println("[Net] Player left: " + name);
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * Sends a single line to the server.  Synchronised so concurrent callers
     * (game thread + join thread) never interleave partial writes.
     *
     * @param line the complete command line to send (no trailing newline needed —
     *             {@code PrintWriter.println} adds one)
     */
    private synchronized void send(String line) {
        if (writer != null && !writer.checkError()) {
            writer.println(line);
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** Returns {@code true} while the TCP connection is open. */
    public boolean isConnected() { return connected.get(); }

    /** Returns the name of the room this client is currently in (for the HUD). */
    public String getRoomName()  { return roomName; }
}
