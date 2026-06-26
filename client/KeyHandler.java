import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;

/**
 * Tracks keyboard input for the AegisCore RPG game using boolean flags.
 *
 * <h3>Design rationale</h3>
 * Rather than reacting to key events directly in the event callbacks,
 * {@code KeyHandler} sets and clears simple {@code boolean} flags that the
 * game loop polls once per tick in {@link GamePanel#update()}.
 *
 * <p>This "flag polling" approach has several advantages over event-driven input:
 * <ul>
 *   <li><strong>No missed inputs</strong> — a key held across multiple frames
 *       is correctly applied for every tick it is down.</li>
 *   <li><strong>Frame-consistent movement</strong> — movement physics run at a
 *       fixed rate (60 Hz) regardless of the OS key-repeat rate.</li>
 *   <li><strong>Thread safety</strong> — {@code boolean} reads and writes are
 *       atomic on the JVM, so the game thread can safely poll flags set by the
 *       AWT Event Dispatch Thread without any explicit synchronisation.</li>
 * </ul>
 *
 * <h3>Supported keys</h3>
 * <pre>
 *   Movement : W / ↑   S / ↓   A / ←   D / →
 *   Action   : SPACE (attack/interact)
 *   Confirm  : ENTER
 *   Menu     : ESCAPE
 * </pre>
 */
public class KeyHandler implements KeyListener {

    // ── Movement flags ────────────────────────────────────────────────────────

    /** {@code true} while W or the UP arrow is held. */
    public boolean upPressed;

    /** {@code true} while S or the DOWN arrow is held. */
    public boolean downPressed;

    /** {@code true} while A or the LEFT arrow is held. */
    public boolean leftPressed;

    /** {@code true} while D or the RIGHT arrow is held. */
    public boolean rightPressed;

    // ── Action flags ──────────────────────────────────────────────────────────

    /** {@code true} while the SPACE key is held (primary action: attack / interact). */
    public boolean spacePressed;

    /** {@code true} while ENTER is held (confirm / use item). */
    public boolean enterPressed;

    /** {@code true} while ESCAPE is held (pause menu / cancel). */
    public boolean escapePressed;

    // ── KeyListener implementation ────────────────────────────────────────────

    @Override
    public void keyPressed(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_W, KeyEvent.VK_UP    -> upPressed     = true;
            case KeyEvent.VK_S, KeyEvent.VK_DOWN  -> downPressed   = true;
            case KeyEvent.VK_A, KeyEvent.VK_LEFT  -> leftPressed   = true;
            case KeyEvent.VK_D, KeyEvent.VK_RIGHT -> rightPressed  = true;
            case KeyEvent.VK_SPACE                -> spacePressed  = true;
            case KeyEvent.VK_ENTER                -> enterPressed  = true;
            case KeyEvent.VK_ESCAPE               -> escapePressed = true;
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_W, KeyEvent.VK_UP    -> upPressed     = false;
            case KeyEvent.VK_S, KeyEvent.VK_DOWN  -> downPressed   = false;
            case KeyEvent.VK_A, KeyEvent.VK_LEFT  -> leftPressed   = false;
            case KeyEvent.VK_D, KeyEvent.VK_RIGHT -> rightPressed  = false;
            case KeyEvent.VK_SPACE                -> spacePressed  = false;
            case KeyEvent.VK_ENTER                -> enterPressed  = false;
            case KeyEvent.VK_ESCAPE               -> escapePressed = false;
        }
    }

    /**
     * Not used — character input is handled via {@code keyPressed} / {@code keyReleased}.
     * {@inheritDoc}
     */
    @Override
    public void keyTyped(KeyEvent e) { /* intentionally empty */ }
}
