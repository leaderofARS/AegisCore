package persistence;

import java.time.Instant;

/**
 * Persistent player profile data stored across server restarts.
 */
public record PlayerProfile(
    String displayName,
    int    totalSessions,
    int    totalGamesStarted,
    int    totalChatMessages,
    Instant firstSeen,
    Instant lastSeen
) {
    /** Creates a fresh player profile with zeroed counters. */
    public static PlayerProfile fresh(String displayName) {
        Instant now = Instant.now();
        return new PlayerProfile(displayName, 0, 0, 0, now, now);
    }

    /** Returns a new profile with the session count incremented and last seen updated. */
    public PlayerProfile withIncrementedSession() {
        return new PlayerProfile(displayName, totalSessions + 1, totalGamesStarted, totalChatMessages, firstSeen, Instant.now());
    }

    /** Returns a new profile with the games count incremented. */
    public PlayerProfile withGameStarted() {
        return new PlayerProfile(displayName, totalSessions, totalGamesStarted + 1, totalChatMessages, firstSeen, Instant.now());
    }

    /** Returns a new profile with the chat count incremented. */
    public PlayerProfile withChat() {
        return new PlayerProfile(displayName, totalSessions, totalGamesStarted, totalChatMessages + 1, firstSeen, Instant.now());
    }
}
