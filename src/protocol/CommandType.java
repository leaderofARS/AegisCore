package protocol;

/**
 * Enumeration of all valid commands a client may send to the AegisCore server.
 *
 * <p>Each constant carries a {@code minArgs} value indicating the minimum number
 * of whitespace-delimited tokens that must follow the command keyword. Commands
 * with fewer arguments than required are rejected by {@link CommandRouter} before
 * any handler logic executes.
 *
 * <p>Parsing is case-insensitive: {@code "name"}, {@code "NAME"}, and {@code "Name"}
 * all resolve to {@link #NAME}.
 */
public enum CommandType {

    /** {@code NAME <username>} — set display name (required before any other command). */
    NAME(1),
    /** {@code CREATE <room-name> [max-players]} — create and immediately join a new room. */
    CREATE(1),
    /** {@code JOIN <room-id>} — join an existing open room by its assigned ID. */
    JOIN(1),
    /** {@code LEAVE} — leave the current room and return to the lobby. */
    LEAVE(0),
    /** {@code LIST} — list all open rooms with player counts and status. */
    LIST(0),
    /** {@code READY} — mark yourself ready in the current room. */
    READY(0),
    /** {@code UNREADY} — cancel your ready status; aborts any in-progress countdown. */
    UNREADY(0),
    /** {@code QUEUE} — enter the automatic matchmaking queue. */
    QUEUE(0),
    /** {@code DEQUEUE} — leave the matchmaking queue. */
    DEQUEUE(0),
    /** {@code CHAT <message...>} — send a message to all players in your current room. */
    CHAT(1),
    /** {@code STATS} — display server statistics. */
    STATS(0),
    /** {@code QUIT} — cleanly disconnect from the server. */
    QUIT(0),
    /** Fallback for unrecognised input; always rejected with an error response. */
    UNKNOWN(0);

    private final int minArgs;

    CommandType(int minArgs) { this.minArgs = minArgs; }

    /** Returns the minimum number of arguments required for this command. */
    public int getMinArgs() { return minArgs; }

    /**
     * Parses a raw token into a {@code CommandType}, ignoring case.
     * Returns {@link #UNKNOWN} if the token does not match any known command.
     *
     * @param token the first whitespace-delimited word from the client's input line
     * @return the matching constant, or {@link #UNKNOWN}
     */
    public static CommandType parse(String token) {
        try {
            return CommandType.valueOf(token.toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
