package room;

/** All event types that can be recorded in a room's event ledger. */
public enum RoomEventType {
    ROOM_CREATED, PLAYER_JOINED, PLAYER_LEFT,
    PLAYER_READY, PLAYER_UNREADY,
    COUNTDOWN_STARTED, COUNTDOWN_CANCELLED,
    MATCH_STARTED, ROOM_CLOSED,
    CHAT_MESSAGE, OWNER_TRANSFERRED,
    SPECTATOR_JOINED, SPECTATOR_LEFT
}
