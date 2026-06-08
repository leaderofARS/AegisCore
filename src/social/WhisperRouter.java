package social;

import player.Player;
import player.PlayerRegistry;

/**
 * Utility for routing private whisper messages between players.
 */
public final class WhisperRouter {

    private WhisperRouter() {}

    /**
     * Attempts to send a private message from a sender to a recipient by name.
     *
     * @param sender      the player sending the message
     * @param targetName  the display name of the recipient
     * @param message     the message content
     * @return true if routed successfully; false if target player was not found
     */
    public static boolean sendWhisper(Player sender, String targetName, String message) {
        Player recipient = PlayerRegistry.getInstance().getPlayerByName(targetName);
        if (recipient == null) {
            sender.send("[ERROR] Player not online: " + targetName);
            return false;
        }

        // Send to recipient
        recipient.send(String.format("[WHISPER] From %s: %s", sender.getDisplayName(), message));

        // Confirm to sender
        sender.send(String.format("[WHISPER] To %s: %s", recipient.getDisplayName(), message));

        // Record in histories
        WhisperHistory.getInstance().record(
            sender.getSessionId(),
            recipient.getSessionId(),
            sender.getDisplayName(),
            recipient.getDisplayName(),
            message
        );

        return true;
    }
}
