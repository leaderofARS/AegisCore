import java.awt.Color;

/**
 * Represents another player in the AegisCore multiplayer game session,
 * as received from the server via {@code [GAME] PLAYER} messages.
 *
 * <h3>Thread safety</h3>
 * Fields {@link #worldX}, {@link #worldY}, and {@link #direction} are
 * {@code volatile} so writes from the {@link NetworkClient} reader thread
 * are immediately visible to the game-loop thread without explicit locking.
 * At worst, a render reads a position that is one network message stale —
 * which is imperceptible at 60 FPS.
 *
 * <h3>Colour assignment</h3>
 * Each player receives a deterministic colour derived from their name hash.
 * The same name always maps to the same hue, so a player's colour is
 * consistent across all clients without any coordination.
 */
public class RemotePlayer {

    // ── Identity ──────────────────────────────────────────────────────────────

    /** Display name (immutable — received once at construction). */
    public final String name;

    /**
     * Body fill colour for this player's avatar.
     * Generated deterministically from {@link #name} so all clients agree.
     */
    public final Color color;

    // ── Mutable game state (written by NetworkClient, read by GamePanel) ──────

    /** World-space X coordinate in pixels — updated on every position broadcast. */
    public volatile int worldX;

    /** World-space Y coordinate in pixels — updated on every position broadcast. */
    public volatile int worldY;

    /**
     * Current movement direction: {@code "UP"}, {@code "DOWN"}, {@code "LEFT"},
     * {@code "RIGHT"}, or {@code "IDLE"}.
     */
    public volatile String direction = "IDLE";

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Creates a remote player at the world midpoint with a name-derived colour.
     * Position and direction will be overwritten on the first server broadcast.
     *
     * @param name the remote player's display name
     */
    public RemotePlayer(String name) {
        this.name   = name;
        this.color  = deriveColor(name);
        // Default to world midpoint — overwritten immediately by server message
        this.worldX = GamePanel.WORLD_WIDTH  / 2;
        this.worldY = GamePanel.WORLD_HEIGHT / 2;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Generates a vivid, deterministic HSB colour from the player's name hash.
     *
     * <p>The hue sweeps the full 360° spectrum; saturation (85%) and brightness
     * (92%) are fixed so colours are always vivid and visible against the dark
     * green tile background.
     *
     * @param name the player display name
     * @return a unique, vibrant {@link Color} for this player
     */
    private static Color deriveColor(String name) {
        // Spread names across the hue wheel with a simple hash
        int   hash = name.hashCode();
        float hue  = (Math.abs(hash) % 360) / 360f;
        return Color.getHSBColor(hue, 0.85f, 0.92f);
    }
}
