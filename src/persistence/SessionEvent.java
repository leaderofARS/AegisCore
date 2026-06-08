package persistence;

import java.time.Instant;

/**
 * An immutable timestamped lobby event recorded in a {@link SessionLedger}.
 *
 * @param type        the kind of event
 * @param timestamp   when this event occurred (UTC)
 * @param sessionId   the player session involved (may be null for room-level events)
 * @param playerLabel the player's display name or session ID at time of event
 * @param data        optional extra data (chat text, state name, etc.)
 */
public record SessionEvent(SessionEventType type, Instant timestamp, String sessionId, String playerLabel, String data) {

    /** Convenience factory — sets timestamp to Instant.now(). */
    public static SessionEvent of(SessionEventType type, String sessionId, String playerLabel, String data) {
        return new SessionEvent(type, Instant.now(), sessionId, playerLabel, data);
    }

    /** Formats as a human-readable string for replay output. */
    public String toReplayLine() {
        return String.format("[%s] %-25s | %-15s | %s | %s",
            timestamp, type,
            playerLabel != null ? playerLabel : "-",
            sessionId != null ? sessionId : "-",
            data != null ? data : "");
    }
}
