package social;

import java.time.Instant;

/**
 * An immutable lobby invitation from one player to another.
 */
public record Invite(
    String inviteId,
    String inviterSessionId,
    String inviterName,
    String inviteeSessionId,
    String roomId,
    Instant issuedAt,
    Instant expiresAt
) {
    /** Returns true if the current system time is after this invite's expiry. */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    /** Returns a human-readable summary of this invitation. */
    public String summary() {
        return inviterName + " -> room " + roomId + " (expires " + expiresAt + ")";
    }
}
