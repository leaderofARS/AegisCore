package spectator;

import player.Player;
import room.Room;
import room.RoomRegistry;
import java.util.Collections;
import java.util.List;

/**
 * Registry query service for finding spectators currently active in AegisCore.
 */
public final class SpectatorRegistry {

    private static final SpectatorRegistry INSTANCE = new SpectatorRegistry();

    private SpectatorRegistry() {}

    /** Returns the singleton {@code SpectatorRegistry} instance. */
    public static SpectatorRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Lists all spectators currently observing the specified room.
     *
     * @param roomId room identifier
     * @return unmodifiable list of spectator players
     */
    public List<Player> getSpectatorsForRoom(String roomId) {
        Room room = RoomRegistry.getInstance().getRoom(roomId);
        if (room == null) {
            return Collections.emptyList();
        }
        return room.getSpectators();
    }
}
