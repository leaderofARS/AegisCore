package persistence;

/** All event types that can be recorded in a session ledger. */
public enum SessionEventType {
    PLAYER_JOIN, PLAYER_LEAVE, PLAYER_READY, PLAYER_UNREADY,
    CHAT_MESSAGE, STATE_CHANGE, COUNTDOWN_START, COUNTDOWN_CANCEL,
    MATCH_START, ROOM_CLOSED
}
