package room;

/**
 * Immutable configuration for a room, set at creation time.
 *
 * @param password              optional join password ({@code null} = no password required)
 * @param minReadyCount         minimum number of ready players to trigger countdown
 *                              (0 = all players must be ready)
 * @param spectatorSlotsAllowed whether spectators may observe this room
 * @param gameMode              optional game-mode tag (e.g., {@code "deathmatch"}, {@code "ctf"})
 * @param region                preferred region tag for display and filtering
 */
public record RoomConfig(
    String  password,
    int     minReadyCount,
    boolean spectatorSlotsAllowed,
    String  gameMode,
    String  region
) {
    /** Returns a default permissive configuration: no password, all-ready, spectators allowed. */
    public static RoomConfig defaultConfig() {
        return new RoomConfig(null, 0, true, "standard", "ANY");
    }

    /** Returns {@code true} if this room requires a join password. */
    public boolean hasPassword() {
        return password != null && !password.isEmpty();
    }

    /** Returns {@code true} if this room has no join password. */
    public boolean isPublic() {
        return !hasPassword();
    }
}
