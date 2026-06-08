package room;

import java.time.Instant;

/**
 * An immutable timestamped event recorded in a {@link RoomEventLedger}.
 *
 * @param type      the kind of event
 * @param timestamp when this event occurred (UTC)
 * @param actorId   the session ID of the player who triggered the event (null for system events)
 * @param actorName the player's display name at the time (null for system events)
 * @param detail    optional extra context (chat text, new state name, etc.)
 */
public record RoomEvent(
    RoomEventType type,
    Instant       timestamp,
    String        actorId,
    String        actorName,
    String        detail
) {
    /**
     * Factory for player-triggered events. Timestamp is set to {@code Instant.now()}.
     *
     * @param type      event type
     * @param actorId   session ID of the triggering player
     * @param actorName display name of the triggering player
     * @param detail    optional extra context
     * @return new {@code RoomEvent}
     */
    public static RoomEvent of(RoomEventType type, String actorId, String actorName, String detail) {
        return new RoomEvent(type, Instant.now(), actorId, actorName, detail);
    }

    /**
     * Factory for server/system-triggered events with no actor.
     *
     * @param type   event type
     * @param detail optional extra context
     * @return new {@code RoomEvent}
     */
    public static RoomEvent system(RoomEventType type, String detail) {
        return new RoomEvent(type, Instant.now(), null, null, detail);
    }

    /**
     * Returns a human-readable single-line representation for log replay.
     *
     * @return formatted log line
     */
    public String toLogLine() {
        return String.format("[%s] %-25s actor=%-15s %s",
            timestamp, type,
            actorName != null ? actorName : "[SYSTEM]",
            detail != null ? detail : "");
    }
}
