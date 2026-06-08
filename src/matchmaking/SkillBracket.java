package matchmaking;

/**
 * Defines an ELO/MMR skill bracket for grouping players in AegisCore matchmaking.
 *
 * <p>Players within the same bracket may be matched together. The bracket is
 * identified by a human-readable name (e.g., {@code "Silver"}) and an inclusive
 * range [{@link #minRating}, {@link #maxRating}].
 *
 * <p>A set of predefined standard brackets is available via {@link #STANDARD_BRACKETS}.
 */
public record SkillBracket(String name, int minRating, int maxRating) {

    /** Lowest-tier bracket: 0–999 MMR. */
    public static final SkillBracket BRONZE   = new SkillBracket("Bronze",      0,   999);
    /** Second-tier bracket: 1000–1499 MMR. */
    public static final SkillBracket SILVER   = new SkillBracket("Silver",   1000,  1499);
    /** Mid-tier bracket: 1500–1999 MMR. */
    public static final SkillBracket GOLD     = new SkillBracket("Gold",     1500,  1999);
    /** High-tier bracket: 2000–2499 MMR. */
    public static final SkillBracket PLATINUM = new SkillBracket("Platinum", 2000,  2499);
    /** Top-tier bracket: 2500+ MMR. */
    public static final SkillBracket DIAMOND  = new SkillBracket("Diamond",  2500, Integer.MAX_VALUE);

    /** Array of all standard brackets in ascending MMR order. */
    public static final SkillBracket[] STANDARD_BRACKETS = {
        BRONZE, SILVER, GOLD, PLATINUM, DIAMOND
    };

    /**
     * Returns {@code true} if the given MMR rating falls inside this bracket.
     *
     * @param rating player MMR to test
     * @return whether the player belongs to this bracket
     */
    public boolean contains(int rating) {
        return rating >= minRating && rating <= maxRating;
    }

    /**
     * Resolves the standard bracket for the given rating.
     *
     * @param rating the MMR value to classify
     * @return the lowest bracket whose range includes the rating; defaults to {@link #BRONZE}
     */
    public static SkillBracket forRating(int rating) {
        for (SkillBracket bracket : STANDARD_BRACKETS) {
            if (bracket.contains(rating)) {
                return bracket;
            }
        }
        return BRONZE;
    }

    /**
     * Returns the absolute difference between two ratings.
     *
     * @param a first rating
     * @param b second rating
     * @return {@code |a - b|}
     */
    public static int delta(int a, int b) {
        return Math.abs(a - b);
    }

    @Override
    public String toString() {
        return name + "[" + minRating + "-" + (maxRating == Integer.MAX_VALUE ? "∞" : maxRating) + "]";
    }
}
