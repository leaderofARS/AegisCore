package player;

import server.ClientHandler;

/**
 * Represents a connected player within the AegisCore lobby system.
 *
 * <p>A {@code Player} wraps the underlying {@link ClientHandler} that owns the
 * TCP socket and adds game-layer identity: a display name chosen via the
 * {@code NAME} command, a {@link PlayerStatus} tracking position in the lobby
 * lifecycle, and the ID of the room the player currently occupies (if any).
 *
 * <p>Status and room assignment are declared {@code volatile} so reads from
 * threads that do not hold this object's monitor (e.g., a broadcast loop)
 * always observe the latest written value. Mutations that must be atomic
 * are performed inside {@code synchronized} blocks in {@link server.ClientHandler}
 * or {@link room.Room}.
 *
 * <p>All outbound message delivery is delegated to {@link ClientHandler#sendMessage},
 * which is itself {@code synchronized} on the handler instance.
 */
public class Player {

    private final String        sessionId;
    private final ClientHandler handler;

    private volatile String       displayName;
    private volatile PlayerStatus status;
    private volatile String       currentRoomId;

    private volatile long joinedAt = System.currentTimeMillis();
    private volatile long lastCommandAt = System.currentTimeMillis();
    private volatile int commandCount = 0;
    private final java.util.Map<String, String> metadata = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Constructs a new Player for the given session.
     *
     * @param sessionId unique identifier derived from the remote socket address
     * @param handler   the {@link ClientHandler} that owns this player's socket
     */
    public Player(String sessionId, ClientHandler handler) {
        this.sessionId    = sessionId;
        this.handler      = handler;
        this.status       = PlayerStatus.CONNECTED;
        this.displayName  = null;
        this.currentRoomId = null;
    }

    /** Returns the stable TCP session identifier (remote address string). */
    public String getSessionId() { return sessionId; }

    /** Returns the underlying {@link ClientHandler} that manages this player's socket. */
    public ClientHandler getHandler() { return handler; }

    /** Returns the player's chosen display name, or {@code null} if not yet set. */
    public String getDisplayName() { return displayName; }

    /** Sets the player's display name. Should only be called once during {@code NAME} handling. */
    public void setDisplayName(String name) { this.displayName = name; }

    /** Returns this player's current lifecycle status. */
    public PlayerStatus getStatus() { return status; }

    /** Updates this player's lifecycle status. */
    public void setStatus(PlayerStatus status) { this.status = status; }

    /** Returns the ID of the room this player is currently in, or {@code null} if in the lobby. */
    public String getCurrentRoomId() { return currentRoomId; }

    /** Sets or clears the player's current room assignment. */
    public void setCurrentRoomId(String roomId) { this.currentRoomId = roomId; }

    /**
     * Delivers a message to this player via the underlying socket handler.
     *
     * @param message text to send; a newline is appended by {@link ClientHandler#sendMessage}
     */
    public void send(String message) { handler.sendMessage(message); }

    /**
     * Returns a human-readable label for this player: the display name if set,
     * otherwise the raw session ID.
     *
     * @return non-null player label
     */
    public String getLabel() { return displayName != null ? displayName : sessionId; }

    public long getJoinedAt() { return joinedAt; }
    public long getLastCommandAt() { return lastCommandAt; }
    public void setLastCommandAt(long timestamp) { this.lastCommandAt = timestamp; }
    public int getCommandCount() { return commandCount; }
    public synchronized void incrementCommandCount() { this.commandCount++; }
    public java.util.Map<String, String> getMetadata() { return metadata; }
}
