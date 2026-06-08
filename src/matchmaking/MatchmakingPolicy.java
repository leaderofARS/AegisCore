package matchmaking;

import player.Player;
import java.util.List;

/**
 * Strategy interface defining how players are selected and paired during matchmaking.
 *
 * <p>Implementations encapsulate the complete matching logic so different policies
 * (pure skill, behavioural, regional) can be swapped at runtime without changing
 * {@link MatchmakingQueue}.
 *
 * <p>All implementations must be thread-safe.
 */
public interface MatchmakingPolicy {

    /**
     * Determines whether {@code candidate} is an acceptable match for all players
     * already collected in {@code currentGroup}.
     *
     * <p>Called for each candidate pulled from the queue before it is added to the
     * forming group. If this returns {@code false}, the candidate is returned to the
     * queue and matching continues.
     *
     * @param candidate    the player being evaluated
     * @param currentGroup players already accepted into the forming match
     * @param config       the match configuration (size, delta constraints, region)
     * @return {@code true} if the candidate may join the group
     */
    boolean isCompatible(Player candidate, List<Player> currentGroup, MatchConfig config);

    /**
     * Returns a human-readable name for this policy (used in logging).
     *
     * @return policy name string
     */
    String name();

    // -----------------------------------------------------------------------
    // Built-in policy implementations
    // -----------------------------------------------------------------------

    /**
     * Simple policy that accepts any queued player regardless of skill or region.
     * Fastest queue fill-rate; lowest match quality.
     */
    MatchmakingPolicy ANY = new MatchmakingPolicy() {
        @Override
        public boolean isCompatible(Player candidate, List<Player> currentGroup, MatchConfig config) {
            return true;
        }
        @Override
        public String name() { return "ANY"; }
    };

    /**
     * Skill-based policy that only accepts players within the configured
     * {@link MatchConfig#maxSkillDelta()} MMR difference of the group anchor.
     *
     * <p>The anchor is the first player in {@code currentGroup}. If the group is empty,
     * any candidate is accepted.
     */
    MatchmakingPolicy SKILL_BASED = new MatchmakingPolicy() {
        @Override
        public boolean isCompatible(Player candidate, List<Player> currentGroup, MatchConfig config) {
            if (currentGroup.isEmpty()) return true;
            PlayerSkillProfile cp = PlayerSkillProfile.getOrCreate(candidate.getSessionId());
            PlayerSkillProfile anchor = PlayerSkillProfile.getOrCreate(currentGroup.get(0).getSessionId());
            int delta = SkillBracket.delta(cp.getRating(), anchor.getRating());
            return delta <= config.maxSkillDelta();
        }
        @Override
        public String name() { return "SKILL_BASED"; }
    };

    /**
     * Region-preference policy that additionally requires regional compatibility on top of
     * the skill delta check. Falls back gracefully if either player has region {@code ANY}.
     */
    MatchmakingPolicy REGIONAL = new MatchmakingPolicy() {
        @Override
        public boolean isCompatible(Player candidate, List<Player> currentGroup, MatchConfig config) {
            if (currentGroup.isEmpty()) return true;

            // Skill check
            PlayerSkillProfile cp = PlayerSkillProfile.getOrCreate(candidate.getSessionId());
            PlayerSkillProfile anchor = PlayerSkillProfile.getOrCreate(currentGroup.get(0).getSessionId());
            if (SkillBracket.delta(cp.getRating(), anchor.getRating()) > config.maxSkillDelta()) {
                return false;
            }

            // Region check — prefer same region; ANY always compatible
            RegionTag configRegion = config.region();
            RegionTag cRegion = cp.getRegion();
            return configRegion.isCompatibleWith(cRegion);
        }
        @Override
        public String name() { return "REGIONAL"; }
    };
}
