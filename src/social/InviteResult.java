package social;

/**
 * Result of attempting to accept a lobby invitation.
 */
public sealed interface InviteResult {
    /** The invitation was successfully accepted. */
    record Accepted(Invite invite) implements InviteResult {}
    /** The invitation has expired. */
    record Expired() implements InviteResult {}
    /** The invitation ID was not found or has already been consumed. */
    record NotFound() implements InviteResult {}
    /** The invitee is already in a room. */
    record AlreadyInRoom() implements InviteResult {}
}
