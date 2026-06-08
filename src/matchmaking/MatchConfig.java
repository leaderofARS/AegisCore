package matchmaking;

/**
 * Immutable configuration for the AegisCore matchmaking system.
 *
 * <p>Controls how many players must be queued before a match is automatically
 * created, as well as the maximum allowed skill delta, preferred region,
 * game mode tag, and queue timeout duration.
 */
public final class MatchConfig {

    private final int       playersPerMatch;
    private final int       maxSkillDelta;
    private final RegionTag region;
    private final String    gameMode;
    private final int       timeoutSeconds;

    /**
     * Constructs a fully specified match configuration.
     *
     * @param playersPerMatch  number of players required to form a match (≥ 2)
     * @param maxSkillDelta    maximum MMR difference allowed between matched players
     *                         (0 = unlimited, matches any player)
     * @param region           preferred region for this matchmaking queue
     * @param gameMode         optional game mode tag (e.g., {@code "deathmatch"})
     * @param timeoutSeconds   seconds to wait for a full match before giving up
     */
    public MatchConfig(int playersPerMatch, int maxSkillDelta,
                       RegionTag region, String gameMode, int timeoutSeconds) {
        this.playersPerMatch = playersPerMatch;
        this.maxSkillDelta   = maxSkillDelta;
        this.region          = region != null ? region : RegionTag.ANY;
        this.gameMode        = gameMode != null ? gameMode : "standard";
        this.timeoutSeconds  = timeoutSeconds > 0 ? timeoutSeconds : 30;
    }

    /**
     * Convenience constructor for size-only configuration. Uses defaults for all
     * other fields: unlimited skill delta, any region, standard mode, 30s timeout.
     *
     * @param playersPerMatch number of players required to form a match (≥ 2)
     */
    public MatchConfig(int playersPerMatch) {
        this(playersPerMatch, Integer.MAX_VALUE, RegionTag.ANY, "standard", 30);
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    /** Returns the number of players required to start a match. */
    public int getPlayersPerMatch() { return playersPerMatch; }

    /**
     * Returns the maximum allowable MMR difference between any two players in a match.
     * {@link Integer#MAX_VALUE} means no restriction.
     */
    public int maxSkillDelta() { return maxSkillDelta; }

    /** Returns the preferred region tag for this matchmaking queue. */
    public RegionTag region() { return region; }

    /** Returns the game-mode tag associated with matches from this config. */
    public String gameMode() { return gameMode; }

    /** Returns the queue timeout in seconds before unmatched players are returned to the lobby. */
    public int timeoutSeconds() { return timeoutSeconds; }

    // -----------------------------------------------------------------------
    // Factory methods
    // -----------------------------------------------------------------------

    /**
     * Returns the default configuration: 2-player, unlimited skill delta, any region,
     * standard game mode, 30-second timeout.
     *
     * @return a sensible default {@code MatchConfig}
     */
    public static MatchConfig defaultConfig() {
        return new MatchConfig(2, Integer.MAX_VALUE, RegionTag.ANY, "standard", 30);
    }

    /**
     * Returns a strict skill-based configuration: 2-player, 300 MMR delta maximum,
     * any region, standard game mode, 60-second timeout.
     *
     * @return skill-constrained {@code MatchConfig}
     */
    public static MatchConfig skillConfig() {
        return new MatchConfig(2, 300, RegionTag.ANY, "standard", 60);
    }

    @Override
    public String toString() {
        return String.format("MatchConfig{players=%d, maxDelta=%s, region=%s, mode=%s, timeout=%ds}",
            playersPerMatch,
            maxSkillDelta == Integer.MAX_VALUE ? "∞" : String.valueOf(maxSkillDelta),
            region, gameMode, timeoutSeconds);
    }
}
