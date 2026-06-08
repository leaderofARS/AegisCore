package matchmaking;

/**
 * Geographic region tags for matchmaking preference filtering in AegisCore.
 *
 * <p>Players and rooms can be tagged with a region so the matchmaking engine
 * prefers pairing players from the same or nearby regions to minimise latency.
 * {@link #ANY} is a wildcard that matches any region.
 */
public enum RegionTag {

    /** North America — East Coast datacenters. */
    NA_EAST,
    /** North America — West Coast datacenters. */
    NA_WEST,
    /** Europe — Western datacenters (UK, FR, DE). */
    EU_WEST,
    /** Europe — Eastern datacenters (PL, RO, RU). */
    EU_EAST,
    /** Asia-Pacific — covers JP, KR, AU, SG. */
    ASIA_PACIFIC,
    /** South America — BR primary. */
    SOUTH_AMERICA,
    /** Middle East & Africa. */
    MEA,
    /**
     * Wildcard — matches any region during matchmaking.
     * Use when a player has no regional preference or as a fallback.
     */
    ANY;

    /**
     * Parses a region string (case-insensitive), returning {@link #ANY} on unknown input.
     *
     * @param raw raw region string from config or client
     * @return the matching constant, or {@code ANY}
     */
    public static RegionTag parse(String raw) {
        if (raw == null || raw.isBlank()) return ANY;
        try {
            return RegionTag.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ANY;
        }
    }

    /**
     * Returns {@code true} if this region is compatible with {@code other}.
     * {@link #ANY} is compatible with all regions.
     *
     * @param other the region to compare against
     * @return whether the two regions are compatible for matchmaking
     */
    public boolean isCompatibleWith(RegionTag other) {
        return this == ANY || other == ANY || this == other;
    }
}
