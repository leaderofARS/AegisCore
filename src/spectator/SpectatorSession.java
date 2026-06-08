package spectator;

import player.Player;
import room.Room;

/**
 * Represents the active read-only session of an observer watching a game room.
 */
public class SpectatorSession {

    private final Player player;
    private final Room room;

    /**
     * Creates a new SpectatorSession.
     *
     * @param player the player who is spectating
     * @param room   the room being spectated
     */
    public SpectatorSession(Player player, Room room) {
        this.player = player;
        this.room = room;
    }

    /** Returns the spectating player. */
    public Player getPlayer() {
        return player;
    }

    /** Returns the room being spectated. */
    public Room getRoom() {
        return room;
    }
}
