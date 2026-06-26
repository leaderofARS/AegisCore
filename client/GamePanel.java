import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.BasicStroke;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JPanel;

/**
 * The heart of the AegisCore 2D Action RPG client.
 *
 * <p>This panel owns:
 * <ol>
 *   <li><strong>Screen constants</strong> — base tile size, scale, resolution.</li>
 *   <li><strong>World constants</strong> — scrollable world dimensions.</li>
 *   <li><strong>The game thread</strong> — a dedicated {@link Thread} that drives
 *       the loop at a locked 60 FPS.</li>
 *   <li><strong>The delta-accumulation game loop</strong> — immune to OS scheduling
 *       jitter; catches up by running extra ticks instead of skipping renders.</li>
 *   <li><strong>Input bridge</strong> — polls {@link KeyHandler} flags each tick.</li>
 *   <li><strong>Network bridge</strong> — throttled GAMEPOS broadcasts via
 *       {@link NetworkClient} and receives remote-player positions.</li>
 *   <li><strong>Rendering</strong> — world background, local player, remote players,
 *       HUD overlay.</li>
 * </ol>
 *
 * <!-- ───────────────────────────────────────────────────────────────────── -->
 * <h3>Screen layout</h3>
 * <pre>
 *   Original tile size : 16 × 16 px
 *   Scale factor       : ×3
 *   Final tile size    : 48 × 48 px  ({@value #TILE_SIZE} px)
 *   Columns on screen  : 16          ({@value #MAX_SCREEN_COL})
 *   Rows on screen     : 12          ({@value #MAX_SCREEN_ROW})
 *   Screen resolution  : 768 × 576 px ({@value #SCREEN_WIDTH} × {@value #SCREEN_HEIGHT})
 * </pre>
 *
 * <!-- ───────────────────────────────────────────────────────────────────── -->
 * <h3>World layout</h3>
 * <pre>
 *   World columns      : 50  ({@value #MAX_WORLD_COL})
 *   World rows         : 38  ({@value #MAX_WORLD_ROW})
 *   World size         : 2 400 × 1 824 px ({@value #WORLD_WIDTH} × {@value #WORLD_HEIGHT})
 * </pre>
 *
 * <!-- ───────────────────────────────────────────────────────────────────── -->
 * <h3>Delta-accumulation game loop</h3>
 * <pre>
 *   TARGET_FPS         = 60
 *   NANOS_PER_FRAME    = 1 000 000 000 / 60 ≈ 16 666 667 ns
 *
 *   previousTime = now()
 *   delta        = 0.0
 *   loop:
 *     elapsed   = now() - previousTime
 *     delta    += elapsed / NANOS_PER_FRAME
 *     previousTime = now()
 *     while delta >= 1.0:
 *         update()    ← advance physics/logic by exactly 1/60 s
 *         repaint()   ← schedule Swing render
 *         delta -= 1.0
 *     yield()         ← give CPU back when idle
 * </pre>
 *
 * <p>If the system is briefly slow, multiple {@code update()} steps fire before
 * the next render — physics stay correct regardless of frame-render latency.
 */
public class GamePanel extends JPanel implements Runnable {

    // ════════════════════════════════════════════════════════════════════════
    //  SCREEN SETTINGS
    // ════════════════════════════════════════════════════════════════════════

    /** Base (unscaled) tile side length in pixels. */
    public static final int ORIGINAL_TILE_SIZE = 16;

    /**
     * Scale multiplier applied to every tile and sprite.
     * Change this value to support HiDPI / 4 K displays.
     */
    public static final int SCALE = 3;

    /**
     * Final rendered tile side length in pixels ({@value} px).
     * Equals {@code ORIGINAL_TILE_SIZE × SCALE} = 16 × 3 = 48.
     */
    public static final int TILE_SIZE = ORIGINAL_TILE_SIZE * SCALE; // 48 px

    /** Number of tile <em>columns</em> visible on screen ({@value}). */
    public static final int MAX_SCREEN_COL = 16;

    /** Number of tile <em>rows</em> visible on screen ({@value}). */
    public static final int MAX_SCREEN_ROW = 12;

    /**
     * Total screen width in pixels ({@value}).
     * Equals {@code MAX_SCREEN_COL × TILE_SIZE} = 16 × 48 = 768.
     */
    public static final int SCREEN_WIDTH  = TILE_SIZE * MAX_SCREEN_COL; // 768 px

    /**
     * Total screen height in pixels ({@value}).
     * Equals {@code MAX_SCREEN_ROW × TILE_SIZE} = 12 × 48 = 576.
     */
    public static final int SCREEN_HEIGHT = TILE_SIZE * MAX_SCREEN_ROW; // 576 px

    // ════════════════════════════════════════════════════════════════════════
    //  WORLD SETTINGS
    // ════════════════════════════════════════════════════════════════════════

    /** World width in tiles (scrollable area, wider than one screen). */
    public static final int MAX_WORLD_COL = 50;

    /** World height in tiles (scrollable area, taller than one screen). */
    public static final int MAX_WORLD_ROW = 38;

    /** World width in pixels ({@value} = 50 × 48). Matches server-side constant. */
    public static final int WORLD_WIDTH  = TILE_SIZE * MAX_WORLD_COL;  // 2 400 px

    /** World height in pixels ({@value} = 38 × 48). Matches server-side constant. */
    public static final int WORLD_HEIGHT = TILE_SIZE * MAX_WORLD_ROW;  // 1 824 px

    // ════════════════════════════════════════════════════════════════════════
    //  GAME LOOP SETTINGS
    // ════════════════════════════════════════════════════════════════════════

    /** Target frame rate for physics updates and rendering. */
    public static final int TARGET_FPS = 60;

    /**
     * Duration of one frame in nanoseconds.
     * {@code 1_000_000_000 / 60 ≈ 16_666_667 ns}.
     */
    public static final double NANOS_PER_FRAME = 1_000_000_000.0 / TARGET_FPS;

    // ════════════════════════════════════════════════════════════════════════
    //  PLAYER SETTINGS
    // ════════════════════════════════════════════════════════════════════════

    /** Local player movement speed in pixels per game-tick (frame). */
    private static final int PLAYER_SPEED = 4; // ≈ 240 px/s = 5 tiles/s

    /**
     * Number of game ticks between GAMEPOS broadcasts to the server.
     * {@code 60 / 6 = 10 position updates per second}.
     */
    private static final int NET_SYNC_INTERVAL = 6;

    // ════════════════════════════════════════════════════════════════════════
    //  SUBSYSTEMS
    // ════════════════════════════════════════════════════════════════════════

    /** Keyboard input — polled once per tick in {@link #update()}. */
    private final KeyHandler    keyHandler;

    /** TCP connection to the AegisCore server. */
    private final NetworkClient networkClient;

    // ════════════════════════════════════════════════════════════════════════
    //  LOCAL PLAYER STATE
    // ════════════════════════════════════════════════════════════════════════

    private final String playerName;

    /** Local player world-space X coordinate (pixels, float for sub-pixel smoothness). */
    private float playerWorldX = WORLD_WIDTH  / 2f;

    /** Local player world-space Y coordinate (pixels, float for sub-pixel smoothness). */
    private float playerWorldY = WORLD_HEIGHT / 2f;

    /** Last reported movement direction; {@code "IDLE"} when stationary. */
    private String playerDir = "IDLE";

    // ════════════════════════════════════════════════════════════════════════
    //  CAMERA STATE
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Camera top-left X in world space — updated every tick so the screen
     * stays centred on the local player, clamped to world bounds.
     */
    private int cameraX;

    /**
     * Camera top-left Y in world space — same as above for the Y axis.
     */
    private int cameraY;

    // ════════════════════════════════════════════════════════════════════════
    //  MULTIPLAYER STATE
    // ════════════════════════════════════════════════════════════════════════

    /**
     * All other connected players, keyed by display name.
     * Written by the {@link NetworkClient} reader thread; read by the game loop.
     * {@link ConcurrentHashMap} provides the thread safety for this cross-thread
     * shared structure.
     */
    final ConcurrentHashMap<String, RemotePlayer> remotePlayers = new ConcurrentHashMap<>();

    // ════════════════════════════════════════════════════════════════════════
    //  METRICS & HUD STATE
    // ════════════════════════════════════════════════════════════════════════

    private long frameCount   = 0;   // total ticks since game start
    private int  fpsCounter   = 0;   // ticks counted in the current second
    private int  displayedFps = 0;   // last stable FPS value shown in HUD
    private long fpsTimestamp = System.nanoTime(); // start of current FPS window

    // ════════════════════════════════════════════════════════════════════════
    //  TILE PALETTE  (stub world — replaced by TileManager in a later pass)
    // ════════════════════════════════════════════════════════════════════════

    private static final Color TILE_EVEN = new Color(56,  96,  56);  // dark green
    private static final Color TILE_ODD  = new Color(46,  80,  46);  // darker green
    private static final Color TILE_GRID = new Color(30,  55,  30);  // grid line

    // ════════════════════════════════════════════════════════════════════════
    //  GAME THREAD
    // ════════════════════════════════════════════════════════════════════════

    /**
     * The dedicated game-loop thread.  Set to {@code null} to request a
     * clean stop from any thread.
     */
    private Thread gameThread;

    // ════════════════════════════════════════════════════════════════════════
    //  CONSTRUCTOR
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Initialises the game panel, attaches keyboard input, and starts the
     * network connection to the AegisCore server.
     *
     * @param playerName    the local player's chosen display name
     * @param serverAddress AegisCore server host (e.g., {@code "localhost"})
     */
    public GamePanel(String playerName, String serverAddress) {
        this.playerName = playerName;

        // Configure the Swing panel
        setPreferredSize(new Dimension(SCREEN_WIDTH, SCREEN_HEIGHT));
        setBackground(Color.BLACK);
        setDoubleBuffered(true); // hardware double-buffering eliminates flicker

        // Attach keyboard listener
        keyHandler = new KeyHandler();
        addKeyListener(keyHandler);
        setFocusable(true); // panel must be focusable to receive key events

        // Connect to AegisCore server
        networkClient = new NetworkClient(this, playerName, serverAddress, 5000);
        networkClient.connect();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  THREAD LIFECYCLE
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Starts the game loop thread.  Must be called <em>after</em> the parent
     * {@link javax.swing.JFrame} is visible so that a valid Graphics context
     * exists for the first {@link #paintComponent} call.
     */
    public void startGameThread() {
        gameThread = new Thread(this, "AegisRPG-GameLoop");
        gameThread.start();
    }

    /**
     * Requests a clean shutdown: stops the game loop and disconnects from the
     * server.  Safe to call from any thread.
     */
    public void shutdown() {
        gameThread = null;
        networkClient.disconnect();
    }

    // ════════════════════════════════════════════════════════════════════════
    //  GAME LOOP  — delta / accumulation method
    // ════════════════════════════════════════════════════════════════════════

    /**
     * The canonical 60-FPS game loop using the <strong>delta-accumulation</strong>
     * (also known as "fix your timestep") method.
     *
     * <h4>Why not {@code Thread.sleep}?</h4>
     * {@code Thread.sleep(16)} targets ~62 FPS but accumulates OS scheduling error
     * over time — the game drifts.  The delta method is self-correcting: elapsed
     * time is measured precisely with {@link System#nanoTime()}, accumulated as a
     * fraction of one frame duration, and consumed only when a full frame's worth
     * has accrued.
     *
     * <h4>Catch-up behaviour</h4>
     * If the system hiccups and {@code delta} exceeds 1.0, the inner {@code while}
     * runs multiple {@code update()} steps before the next render — keeping game
     * physics correct without visual artefacts.
     */
    @Override
    public void run() {
        long   previousTime = System.nanoTime();
        double delta        = 0.0;

        while (gameThread != null) {
            long currentTime = System.nanoTime();

            // Accumulate fractional frames elapsed since last iteration
            delta += (currentTime - previousTime) / NANOS_PER_FRAME;
            previousTime = currentTime;

            // Execute as many full-tick updates as the elapsed time demands
            while (delta >= 1.0) {
                update();   // advance game state by exactly 1/60 second
                repaint();  // schedule a Swing repaint (non-blocking)
                delta -= 1.0;
                frameCount++;
                fpsCounter++;
            }

            // Update the displayed FPS counter once per second
            long now = System.nanoTime();
            if (now - fpsTimestamp >= 1_000_000_000L) {
                displayedFps = fpsCounter;
                fpsCounter   = 0;
                fpsTimestamp = now;
            }

            // Yield the CPU when there is nothing to do — prevents 100% CPU spin
            Thread.yield();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  UPDATE  — one game tick (1/60 second)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Advances the game state by exactly one tick ({@code 1 / TARGET_FPS} seconds).
     *
     * <p>Responsibilities per tick:
     * <ol>
     *   <li>Poll the {@link KeyHandler} and compute the new player position.</li>
     *   <li>Clamp the player within world bounds.</li>
     *   <li>Re-compute the camera to keep the player centred.</li>
     *   <li>Every {@value #NET_SYNC_INTERVAL} ticks: broadcast position to server
     *       (only if position or direction changed).</li>
     * </ol>
     */
    private void update() {

        // ── 1. Resolve WASD input into velocity ───────────────────────────────
        float dx = 0, dy = 0;
        if (keyHandler.upPressed)    dy -= PLAYER_SPEED;
        if (keyHandler.downPressed)  dy += PLAYER_SPEED;
        if (keyHandler.leftPressed)  dx -= PLAYER_SPEED;
        if (keyHandler.rightPressed) dx += PLAYER_SPEED;

        // Normalise diagonal movement so speed is constant in all directions
        if (dx != 0 && dy != 0) {
            dx *= 0.7071f; // 1 / √2 ≈ 0.7071
            dy *= 0.7071f;
        }

        boolean moving = (dx != 0 || dy != 0);

        // Derive a direction string for animation / server state
        String newDir = "IDLE";
        if      (dy < 0) newDir = "UP";
        else if (dy > 0) newDir = "DOWN";
        else if (dx < 0) newDir = "LEFT";
        else if (dx > 0) newDir = "RIGHT";

        // ── 2. Apply movement ─────────────────────────────────────────────────
        playerWorldX += dx;
        playerWorldY += dy;
        playerDir     = newDir;

        // ── 3. Clamp player within world bounds ───────────────────────────────
        playerWorldX = Math.clamp(playerWorldX, 0f, WORLD_WIDTH  - TILE_SIZE);
        playerWorldY = Math.clamp(playerWorldY, 0f, WORLD_HEIGHT - TILE_SIZE);

        // ── 4. Update camera (centre on player, clamp to world edges) ─────────
        cameraX = (int) Math.clamp(playerWorldX - SCREEN_WIDTH  / 2f, 0f, WORLD_WIDTH  - SCREEN_WIDTH);
        cameraY = (int) Math.clamp(playerWorldY - SCREEN_HEIGHT / 2f, 0f, WORLD_HEIGHT - SCREEN_HEIGHT);

        // ── 5. Network position broadcast (throttled + change-detected) ────────
        if (frameCount % NET_SYNC_INTERVAL == 0 && networkClient.isConnected()) {
            networkClient.sendGamePos((int) playerWorldX, (int) playerWorldY, playerDir);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  RENDERING  — painter's algorithm: back → front
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Renders one complete frame.
     *
     * <h4>Draw order (back-to-front)</h4>
     * <ol>
     *   <li>World background tile checker (stub; replaced by TileManager later)</li>
     *   <li>Remote players (other connected clients)</li>
     *   <li>Local player (always on top of others)</li>
     *   <li>HUD overlay (FPS, status, player roster)</li>
     * </ol>
     *
     * <p>All world-space coordinates are converted to screen-space by subtracting
     * the camera offset ({@link #cameraX}, {@link #cameraY}) before drawing.
     *
     * @param g the graphics context provided by Swing's double-buffer mechanism
     */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        // Enable smooth rendering for circles and text
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // ── Layer 1: World background ─────────────────────────────────────────
        drawWorld(g2);

        // ── Layer 2: Remote players ───────────────────────────────────────────
        for (RemotePlayer rp : remotePlayers.values()) {
            drawAvatar(g2,
                    (int) rp.worldX - cameraX,
                    (int) rp.worldY - cameraY,
                    rp.name, rp.direction, rp.color, false);
        }

        // ── Layer 3: Local player ─────────────────────────────────────────────
        drawAvatar(g2,
                (int) playerWorldX - cameraX,
                (int) playerWorldY - cameraY,
                playerName, playerDir, new Color(80, 160, 255), true);

        // ── Layer 4: HUD overlay ──────────────────────────────────────────────
        drawHud(g2);

        g2.dispose();
    }

    // ── Rendering helpers ─────────────────────────────────────────────────────

    /**
     * Draws the stub checker-pattern world background.
     * Only tiles within the visible viewport are drawn (frustum culling).
     */
    private void drawWorld(Graphics2D g2) {
        for (int col = 0; col < MAX_WORLD_COL; col++) {
            for (int row = 0; row < MAX_WORLD_ROW; row++) {

                int wx = col * TILE_SIZE;
                int wy = row * TILE_SIZE;

                // Skip tiles outside the current viewport (frustum cull)
                if (wx + TILE_SIZE < cameraX || wx > cameraX + SCREEN_WIDTH)  continue;
                if (wy + TILE_SIZE < cameraY || wy > cameraY + SCREEN_HEIGHT) continue;

                int sx = wx - cameraX;
                int sy = wy - cameraY;

                // Checker pattern
                g2.setColor((col + row) % 2 == 0 ? TILE_EVEN : TILE_ODD);
                g2.fillRect(sx, sy, TILE_SIZE, TILE_SIZE);

                // Subtle grid lines for a tile-map feel
                g2.setColor(TILE_GRID);
                g2.drawRect(sx, sy, TILE_SIZE, TILE_SIZE);
            }
        }
    }

    /**
     * Draws a player avatar: drop-shadow, coloured body circle, direction
     * indicator, nameplate, and (for the local player) a highlight ring.
     *
     * @param g2        graphics context
     * @param sx        screen-space X (top-left of avatar bounding box)
     * @param sy        screen-space Y (top-left of avatar bounding box)
     * @param name      display name shown in the nameplate
     * @param direction movement direction for the directional marker
     * @param color     body fill colour
     * @param isLocal   {@code true} = local player (adds white ring + star in nameplate)
     */
    private void drawAvatar(Graphics2D g2, int sx, int sy,
                             String name, String direction, Color color, boolean isLocal) {

        int size = TILE_SIZE - 10; // avatar is slightly smaller than a full tile
        int ax   = sx + 5;         // avatar origin X (5 px inset from tile edge)
        int ay   = sy + 5;         // avatar origin Y

        // ── Drop shadow ───────────────────────────────────────────────────────
        g2.setColor(new Color(0, 0, 0, 70));
        g2.fillOval(ax + 4, ay + 8, size, size);

        // ── Body circle ───────────────────────────────────────────────────────
        g2.setColor(color);
        g2.fillOval(ax, ay, size, size);

        // ── Highlight ring (local player only) ────────────────────────────────
        if (isLocal) {
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2.5f));
            g2.drawOval(ax, ay, size, size);
            g2.setStroke(new BasicStroke(1f)); // reset
        }

        // ── Direction indicator (small filled triangle) ───────────────────────
        if (!"IDLE".equals(direction)) {
            drawDirectionTriangle(g2, ax + size / 2, ay + size / 2, size / 2, direction);
        }

        // ── Nameplate ─────────────────────────────────────────────────────────
        String label = isLocal ? "★ " + name : name;
        g2.setFont(new Font("SansSerif", Font.BOLD, 11));
        FontMetrics fm  = g2.getFontMetrics();
        int   tw   = fm.stringWidth(label);
        int   pad  = 4;
        int   nx   = ax + size / 2 - tw / 2;
        int   ny   = ay - 6;

        // Plate background
        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRoundRect(nx - pad, ny - fm.getAscent() - 1,
                tw + pad * 2, fm.getHeight() + 2, 6, 6);

        // Plate text
        g2.setColor(isLocal ? new Color(160, 215, 255) : Color.WHITE);
        g2.drawString(label, nx, ny);
    }

    /**
     * Draws a small filled equilateral triangle pointing in the player's
     * current movement direction.  The triangle is centred on {@code (cx, cy)}.
     */
    private void drawDirectionTriangle(Graphics2D g2, int cx, int cy, int r,
                                        String direction) {
        int tip = r / 2 + 5;
        int hw  = 5; // half-base width
        int[] xp, yp;
        switch (direction) {
            case "UP"    -> { xp = new int[]{cx,      cx - hw, cx + hw};
                              yp = new int[]{cy - tip, cy - tip + 9, cy - tip + 9}; }
            case "DOWN"  -> { xp = new int[]{cx,      cx - hw, cx + hw};
                              yp = new int[]{cy + tip, cy + tip - 9, cy + tip - 9}; }
            case "LEFT"  -> { xp = new int[]{cx - tip, cx - tip + 9, cx - tip + 9};
                              yp = new int[]{cy,       cy - hw,      cy + hw     }; }
            case "RIGHT" -> { xp = new int[]{cx + tip, cx + tip - 9, cx + tip - 9};
                              yp = new int[]{cy,       cy - hw,      cy + hw     }; }
            default      -> { return; }
        }
        g2.setColor(new Color(255, 255, 255, 200));
        g2.fillPolygon(xp, yp, 3);
    }

    /**
     * Draws the HUD overlay: FPS counter, connection status, world coordinates,
     * player roster, and control hints.
     */
    private void drawHud(Graphics2D g2) {
        final int PAD = 10;

        // ── Top-left info block ───────────────────────────────────────────────
        g2.setFont(new Font("Monospaced", Font.PLAIN, 12));
        FontMetrics fm  = g2.getFontMetrics();
        int lineH = fm.getHeight() + 2;

        String[] infoLines = {
                "FPS    : " + displayedFps + " / " + TARGET_FPS,
                "Server : " + (networkClient.isConnected() ? "Connected ✓" : "Offline ✗"),
                "Room   : " + networkClient.getRoomName(),
                "Pos    : " + (int) playerWorldX + ", " + (int) playerWorldY,
                "Players: " + (1 + remotePlayers.size()),
        };

        int blockH = infoLines.length * lineH + PAD;
        int blockW = 200;
        g2.setColor(new Color(0, 0, 0, 160));
        g2.fillRoundRect(PAD - 4, PAD - 4, blockW, blockH, 8, 8);

        for (int i = 0; i < infoLines.length; i++) {
            drawShadowText(g2, infoLines[i], PAD, PAD + (i + 1) * lineH, Color.WHITE);
        }

        // ── Right side: player roster ─────────────────────────────────────────
        g2.setFont(new Font("SansSerif", Font.BOLD, 12));
        FontMetrics rfm  = g2.getFontMetrics();
        int rosterX = SCREEN_WIDTH - 180;
        int rosterY = PAD;
        int rLines  = 1 + 1 + remotePlayers.size(); // header + local + each remote

        g2.setColor(new Color(0, 0, 0, 160));
        g2.fillRoundRect(rosterX - 6, rosterY - 4, 178, rLines * (rfm.getHeight() + 2) + 8, 8, 8);

        // Roster header
        drawShadowText(g2, "★ Players Online", rosterX, rosterY + rfm.getHeight(), new Color(255, 220, 80));
        rosterY += rfm.getHeight() + 6;

        // Local player
        drawShadowText(g2, "• " + playerName + " (you)", rosterX, rosterY + rfm.getHeight(),
                new Color(80, 160, 255));
        rosterY += rfm.getHeight() + 2;

        // Remote players
        for (RemotePlayer rp : remotePlayers.values()) {
            drawShadowText(g2, "• " + rp.name, rosterX, rosterY + rfm.getHeight(), rp.color);
            rosterY += rfm.getHeight() + 2;
        }

        // ── Bottom: control hints ─────────────────────────────────────────────
        g2.setFont(new Font("SansSerif", Font.ITALIC, 11));
        g2.setColor(new Color(200, 200, 200, 180));
        g2.drawString("WASD / Arrow Keys: Move", PAD, SCREEN_HEIGHT - PAD);
    }

    /**
     * Draws a text string with a 1-pixel drop-shadow for HUD legibility against
     * any background colour.
     */
    private void drawShadowText(Graphics2D g2, String text, int x, int y, Color color) {
        g2.setColor(new Color(0, 0, 0, 180));
        g2.drawString(text, x + 1, y + 1);
        g2.setColor(color);
        g2.drawString(text, x, y);
    }
}
