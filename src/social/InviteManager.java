package social;

import player.Player;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton manager coordinating pending room invitations between players.
 *
 * <p>Handles invite creation, lookup, declination, and periodic expiry cleanup.
 * All operations are thread-safe.
 */
public final class InviteManager {

    private static final InviteManager INSTANCE = new InviteManager();

    private final ConcurrentHashMap<String, Invite> invites = new ConcurrentHashMap<>();

    private InviteManager() {}

    /** Returns the singleton {@code InviteManager} instance. */
    public static InviteManager getInstance() {
        return INSTANCE;
    }

    /**
     * Issues a room invitation from one player to another, valid for 60 seconds.
     *
     * @param inviter  the player issuing the invite
     * @param invitee  the target player being invited
     * @param roomId   the room ID to invite them to
     * @return the created {@link Invite}
     */
    public Invite issueInvite(Player inviter, Player invitee, String roomId) {
        String inviteId = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        Instant expiry = now.plus(Duration.ofSeconds(60));

        Invite invite = new Invite(
            inviteId,
            inviter.getSessionId(),
            inviter.getDisplayName(),
            invitee.getSessionId(),
            roomId,
            now,
            expiry
        );

        invites.put(inviteId, invite);

        // Notify the invitee
        invitee.send(String.format("[INVITE] %s invited you to room %s. Type: ACCEPT %s or DECLINE %s",
            inviter.getDisplayName(), roomId, inviteId, inviteId));

        return invite;
    }

    /**
     * Attempts to accept an invitation.
     *
     * @param invitee  the player accepting the invite
     * @param inviteId the ID of the invitation
     * @return the result of the accept attempt
     */
    public InviteResult accept(Player invitee, String inviteId) {
        if (invitee.getCurrentRoomId() != null) {
            return new InviteResult.AlreadyInRoom();
        }

        Invite invite = invites.get(inviteId);
        if (invite == null || !invite.inviteeSessionId().equals(invitee.getSessionId())) {
            return new InviteResult.NotFound();
        }

        if (invite.isExpired()) {
            invites.remove(inviteId);
            return new InviteResult.Expired();
        }

        // Consume the invite
        invites.remove(inviteId);
        return new InviteResult.Accepted(invite);
    }

    /**
     * Declines a pending invitation and notifies the inviter player.
     *
     * @param invitee  the player declining
     * @param inviteId the ID of the invitation
     */
    public void decline(Player invitee, String inviteId) {
        Invite invite = invites.remove(inviteId);
        if (invite == null || !invite.inviteeSessionId().equals(invitee.getSessionId())) {
            return;
        }

        // Attempt to notify inviter
        player.Player inviter = player.PlayerRegistry.getInstance().getPlayer(invite.inviterSessionId());
        if (inviter != null) {
            inviter.send(String.format("[INVITE] %s declined your invitation to room %s.",
                invitee.getDisplayName(), invite.roomId()));
        }
    }

    /**
     * Returns a list of all active pending invites for a given session.
     *
     * @param inviteeSessionId session ID of the invitee
     * @return list of non-expired invites
     */
    public List<Invite> getPendingInvitesFor(String inviteeSessionId) {
        List<Invite> pending = new ArrayList<>();
        Instant now = Instant.now();
        for (Invite invite : invites.values()) {
            if (invite.inviteeSessionId().equals(inviteeSessionId)) {
                if (invite.expiresAt().isAfter(now)) {
                    pending.add(invite);
                } else {
                    invites.remove(invite.inviteId());
                }
            }
        }
        return pending;
    }

    /**
     * Removes all expired invites from memory.
     */
    public void cleanupExpired() {
        Instant now = Instant.now();
        invites.values().removeIf(invite -> invite.expiresAt().isBefore(now));
    }
}
