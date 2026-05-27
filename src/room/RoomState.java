package room;

/**
 * Lifecycle states of a {@link Room} within AegisCore.
 *
 * <p>Transitions:
 * <pre>
 *   WAITING ──(all players ready)──→ READY_CHECK ──(5-sec countdown)──→ IN_PROGRESS
 *   READY_CHECK ──(any UNREADY)────→ WAITING
 *   WAITING     ──(owner leaves / room empty)──→ CLOSED
 *   IN_PROGRESS ──(server shutdown / explicit close)──→ CLOSED
 * </pre>
 */
public enum RoomState {
    /** Room is open; players may join and toggle their ready status. */
    WAITING,
    /** All players are ready; the 5-second countdown is in progress. */
    READY_CHECK,
    /** Game session is active; the room is locked to new entrants. */
    IN_PROGRESS,
    /** Room has been closed and all player references cleared. */
    CLOSED
}
