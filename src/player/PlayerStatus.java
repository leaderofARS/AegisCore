package player;

/**
 * Represents the lifecycle state of a connected player within AegisCore.
 *
 * <p>Transitions follow a strict state machine enforced by {@code CommandRouter}:
 * <pre>
 *   CONNECTED ──(NAME)──────→ IN_LOBBY
 *   IN_LOBBY  ──(CREATE/JOIN)→ IN_ROOM
 *   IN_LOBBY  ──(QUEUE)─────→ QUEUED
 *   IN_ROOM   ──(LEAVE)─────→ IN_LOBBY
 *   QUEUED    ──(match found)→ IN_ROOM
 *   QUEUED    ──(DEQUEUE)───→ IN_LOBBY
 *   IN_ROOM   ──(game start)→ IN_GAME
 *   IN_GAME   ──(session end)→ IN_LOBBY
 * </pre>
 */
public enum PlayerStatus {
    /** TCP connection established; player has not yet set a display name. */
    CONNECTED,
    /** Player is named and in the main lobby, not assigned to any room. */
    IN_LOBBY,
    /** Player is waiting in the automatic matchmaking queue. */
    QUEUED,
    /** Player is inside a lobby room (WAITING or READY_CHECK state). */
    IN_ROOM,
    /** Player's room has transitioned to IN_PROGRESS; game session is active. */
    IN_GAME
}
