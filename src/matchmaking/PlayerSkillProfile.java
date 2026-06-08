package matchmaking;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks in-memory skill rating and match history for a single player session.
 *
 * <p>MMR starts at {@value #DEFAULT_RATING}. The global profile registry is
 * maintained as a static {@link ConcurrentHashMap} keyed by session ID so every
 * component can access it without circular dependencies.
 *
 * <p>Match history is kept as a sliding window of the last {@value #MAX_HISTORY}
 * session IDs to prevent repeatedly rematching the same opponents.
 */
public final class PlayerSkillProfile {

    /** Default starting MMR for new players. */
    public static final int DEFAULT_RATING = 1200;
    /** Maximum match history entries kept per player. */
    private static final int MAX_HISTORY  = 20;

    // Global registry: sessionId → profile
    private static final ConcurrentHashMap<String, PlayerSkillProfile> REGISTRY =
        new ConcurrentHashMap<>();

    private final String sessionId;
    private final AtomicInteger rating = new AtomicInteger(DEFAULT_RATING);
    private final Deque<String>  recentOpponents = new ArrayDeque<>();

    private volatile RegionTag  region       = RegionTag.ANY;
    private volatile Instant    registeredAt = Instant.now();

    private PlayerSkillProfile(String sessionId) {
        this.sessionId = sessionId;
    }

    // -----------------------------------------------------------------------
    // Static registry access
    // -----------------------------------------------------------------------

    /**
     * Returns the {@code PlayerSkillProfile} for the given session, creating it
     * with default values if it does not exist.
     *
     * @param sessionId unique TCP session identifier
     * @return existing or newly created profile
     */
    public static PlayerSkillProfile getOrCreate(String sessionId) {
        return REGISTRY.computeIfAbsent(sessionId, PlayerSkillProfile::new);
    }

    /**
     * Returns the profile for the given session, or {@code null} if absent.
     *
     * @param sessionId session to look up
     * @return existing profile or {@code null}
     */
    public static PlayerSkillProfile get(String sessionId) {
        return REGISTRY.get(sessionId);
    }

    /**
     * Removes the profile for the given session (call on disconnect).
     *
     * @param sessionId session to evict
     */
    public static void evict(String sessionId) {
        REGISTRY.remove(sessionId);
    }

    // -----------------------------------------------------------------------
    // Instance API
    // -----------------------------------------------------------------------

    /** Returns the session ID this profile belongs to. */
    public String getSessionId() { return sessionId; }

    /** Returns the current MMR rating. */
    public int getRating() { return rating.get(); }

    /** Returns the skill bracket this player currently falls in. */
    public SkillBracket getBracket() { return SkillBracket.forRating(rating.get()); }

    /** Returns the player's preferred region. */
    public RegionTag getRegion() { return region; }

    /** Sets the player's preferred region. */
    public void setRegion(RegionTag region) { this.region = region; }

    /** Returns when this profile was first created. */
    public Instant getRegisteredAt() { return registeredAt; }

    /**
     * Adjusts the MMR rating by the given delta (positive = win, negative = loss).
     * Rating is floor-clamped at 0.
     *
     * @param delta change to apply
     */
    public void adjustRating(int delta) {
        rating.updateAndGet(r -> Math.max(0, r + delta));
    }

    /**
     * Records an opponent session as recently matched to prevent immediate re-matching.
     *
     * @param opponentSessionId session ID of the opponent
     */
    public synchronized void recordMatch(String opponentSessionId) {
        recentOpponents.addLast(opponentSessionId);
        while (recentOpponents.size() > MAX_HISTORY) {
            recentOpponents.removeFirst();
        }
    }

    /**
     * Returns {@code true} if the given opponent appears in recent match history.
     *
     * @param opponentSessionId session to check
     * @return whether this player was recently matched against the opponent
     */
    public synchronized boolean wasRecentlyMatchedWith(String opponentSessionId) {
        return recentOpponents.contains(opponentSessionId);
    }

    /**
     * Returns a copy of the recent opponent session ID list.
     *
     * @return ordered list of recent opponents (oldest first)
     */
    public synchronized List<String> getRecentOpponents() {
        return new ArrayList<>(recentOpponents);
    }

    @Override
    public String toString() {
        return String.format("PlayerSkillProfile{session=%s, rating=%d, bracket=%s, region=%s}",
            sessionId, rating.get(), getBracket().name(), region);
    }
}
