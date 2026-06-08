package security;

import java.time.Instant;

/**
 * An immutable record of a single ban entry in the AegisCore ban list.
 *
 * @param target  the banned IP address or display name
 * @param type    whether {@code target} is an IP or a player name
 * @param reason  human-readable reason for the ban
 * @param expiry  expiry timestamp, or {@code null} for a permanent ban
 */
public record BanEntry(String target, BanTarget type, String reason, Instant expiry) {

    /** Returns {@code true} if this ban has passed its expiry time. */
    public boolean isExpired() {
        return expiry != null && Instant.now().isAfter(expiry);
    }

    /** Returns {@code true} if this ban has no expiry date. */
    public boolean isPermanent() {
        return expiry == null;
    }

    /** Returns a human-readable summary line for this ban. */
    public String summary() {
        String expiryStr = expiry == null ? "PERMANENT" : expiry.toString();
        return String.format("[%s] %s | Reason: %s | Expires: %s", type, target, reason, expiryStr);
    }
}
