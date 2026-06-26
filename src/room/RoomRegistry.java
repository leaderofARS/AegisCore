package room;

import core.Logger;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Global singleton registry managing all active {@link Room} instances in AegisCore.
 *
 * <p>Rooms are stored in a {@link ConcurrentHashMap}, allowing concurrent creation,
 * lookup, and iteration without an external lock. Room IDs are assigned sequentially
 * using an {@link AtomicInteger} counter in the format {@code r-001}, {@code r-002}, etc.
 *
 * <p>Eagerly initialised by the class loader — safe for publication to any thread.
 */
public class RoomRegistry {

    private static final RoomRegistry instance = new RoomRegistry();
    private RoomRegistry() {}

    /** Returns the singleton {@code RoomRegistry} instance. */
    public static RoomRegistry getInstance() { return instance; }

    private final ConcurrentHashMap<String, Room> rooms       = new ConcurrentHashMap<>();
    private final AtomicInteger                    roomCounter = new AtomicInteger(0);

    /**
     * Creates a new room with default config, assigns it a unique ID, and registers it.
     *
     * @param name            display name for the room
     * @param ownerSessionId  session ID of the creating player
     * @param maxPlayers      maximum occupant count (2–32)
     * @return the newly created and registered {@link Room}
     * @throws IllegalStateException if the room limit is reached
     */
    public Room createRoom(String name, String ownerSessionId, int maxPlayers) {
        return createRoom(name, ownerSessionId, maxPlayers, RoomConfig.defaultConfig());
    }

    /**
     * Creates a new room with a custom configuration, checking against the maximum rooms cap.
     *
     * @param name            display name for the room
     * @param ownerSessionId  session ID of the creating player
     * @param maxPlayers      maximum occupant count (2–32)
     * @param config          room configuration parameters
     * @return the newly created and registered {@link Room}
     * @throws IllegalStateException if the room limit is reached
     */
    public Room createRoom(String name, String ownerSessionId, int maxPlayers, RoomConfig config) {
        int maxRoomsLimit = admin.ServerConfig.getInstance().getMaxRooms();
        if (getActiveRoomCount() >= maxRoomsLimit) {
            throw new IllegalStateException("Server room limit reached (" + maxRoomsLimit + ")");
        }
        String roomId = "r-" + String.format("%03d", roomCounter.incrementAndGet());
        Room room = new Room(roomId, name, ownerSessionId, maxPlayers, config);
        rooms.put(roomId, room);
        Logger.logRegistry("Room created: " + roomId + " '" + name +
                           "' by " + ownerSessionId + " (max: " + maxPlayers + ")");
        cluster.ClusterManager.getInstance().syncLocalRoom(room);
        return room;
    }

    /**
     * Returns the room with the given ID, or {@code null} if it does not exist.
     *
     * @param roomId server-assigned room identifier
     * @return the matching room, or {@code null}
     */
    public Room getRoom(String roomId) { return rooms.get(roomId); }

    /**
     * Returns rooms filtered by a game mode tag.
     */
    public List<Room> getRoomsByGameMode(String gameMode) {
        if (gameMode == null) return java.util.Collections.emptyList();
        return rooms.values().stream()
            .filter(r -> r.getState() != RoomState.CLOSED)
            .filter(r -> gameMode.equalsIgnoreCase(r.getConfig().gameMode()))
            .collect(Collectors.toList());
    }

    /**
     * Returns rooms matching a specific region.
     */
    public List<Room> getRoomsByRegion(String region) {
        if (region == null) return java.util.Collections.emptyList();
        return rooms.values().stream()
            .filter(r -> r.getState() != RoomState.CLOSED)
            .filter(r -> region.equalsIgnoreCase(r.getConfig().region()))
            .collect(Collectors.toList());
    }

    /**
     * Returns a list of rooms that are in {@code WAITING} state and not yet full.
     * Suitable for rendering the {@code LIST} command response.
     *
     * @return unmodifiable list of joinable rooms; may be empty
     */
    public List<Room> getOpenRooms() {
        return rooms.values().stream()
            .filter(r -> r.getState() == RoomState.WAITING && !r.isFull())
            .collect(Collectors.toList());
    }

    /** Returns the total number of rooms currently tracked (including closed ones). */
    public int getRoomCount() { return rooms.size(); }

    /** Returns the number of rooms currently in {@code WAITING} or {@code READY_CHECK} state. */
    public int getActiveRoomCount() {
        return (int) rooms.values().stream()
            .filter(r -> r.getState() == RoomState.WAITING || r.getState() == RoomState.READY_CHECK)
            .count();
    }

    /**
     * Removes all rooms whose state is {@code CLOSED} from the registry.
     * Called after a room close event to keep the map from growing indefinitely.
     */
    public void cleanupClosedRooms() {
        rooms.forEach((id, room) -> {
            if (room.getState() == RoomState.CLOSED) {
                cluster.ClusterManager.getInstance().destroyLocalRoom(id);
            }
        });
        rooms.entrySet().removeIf(e -> e.getValue().getState() == RoomState.CLOSED);
    }
}
