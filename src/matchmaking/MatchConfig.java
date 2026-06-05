package matchmaking;

/**
 * Immutable configuration for the AegisCore matchmaking system.
 *
 * <p>Controls how many players must be queued before a match is automatically
 * created. Additional constraints (e.g., max active rooms) can be added here
 * without changing the {@link MatchmakingQueue} API.
 */
public final class MatchConfig {

    /** Number of queued players required to form a match. Default: 2. */
    private final int playersPerMatch;

    /**
     * Constructs a configuration with the given match size.
     *
     * @param playersPerMatch number of players per auto-created match; must be &ge; 2
     */
    public MatchConfig(int playersPerMatch) {
        this.playersPerMatch = playersPerMatch;
    }

    /** Returns the number of players required to start a match. */
    public int getPlayersPerMatch() { return playersPerMatch; }

    /**
     * Returns the default configuration: 2-player matches.
     *
     * @return a {@code MatchConfig} with {@code playersPerMatch = 2}
     */
    public static MatchConfig defaultConfig() { return new MatchConfig(2); }
}
