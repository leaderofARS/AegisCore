package protocol;

import player.Player;
import java.util.Arrays;

/**
 * Immutable value object carrying the parsed state of a single client command invocation.
 *
 * <p>Created by {@link CommandRouter} for every non-empty input line received from a
 * client. Passed to handler methods to provide uniform access to the executing player,
 * the resolved command type, and the argument tokens.
 */
public final class CommandContext {

    /** The player who issued this command. */
    public final Player      player;
    /** The resolved command type. */
    public final CommandType type;
    /** Argument tokens; index 0 is the first token after the command keyword. */
    public final String[]    args;

    /**
     * Constructs a command context.
     *
     * @param player the issuing player
     * @param type   resolved command type
     * @param args   argument tokens (may be empty, never {@code null})
     */
    public CommandContext(Player player, CommandType type, String[] args) {
        this.player = player;
        this.type   = type;
        this.args   = args;
    }

    /**
     * Returns the argument at the given index, or {@code null} if out of bounds.
     *
     * @param index zero-based argument index
     * @return the argument string, or {@code null}
     */
    public String arg(int index) {
        return (index < args.length) ? args[index] : null;
    }

    /**
     * Joins all argument tokens from {@code from} to the end into a single space-delimited string.
     * Used by commands like {@code CHAT} that treat all trailing tokens as a freeform message.
     *
     * @param from starting index (inclusive)
     * @return joined string, or empty string if {@code from} is out of bounds
     */
    public String joinArgs(int from) {
        if (from >= args.length) { return ""; }
        return String.join(" ", Arrays.copyOfRange(args, from, args.length));
    }
}
