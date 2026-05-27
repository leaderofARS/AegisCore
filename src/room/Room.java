package room;

import player.Player;
import player.PlayerStatus;
import core.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A lobby room that manages a group of players through the pre-game lifecycle.
 *
 * <p>A room is created by one player (the owner), who sets its name and maximum
 * capacity. Other players join by ID. The room progresses through {@link RoomState}
 * via a ready-check mechanism: when every occupant marks themselves ready, a
 * 5-second countdown begins. Any player may cancel the countdown by sending
 * {@code UNREADY}, reverting the room to {@code WAITING}. If the countdown
 * completes without interruption the room transitions to {@code IN_PROGRESS}.
 *
 * <h3>Thread safety</h3>
 * <ul>
 *   <li>The player list uses {@link CopyOnWriteArrayList} — iteration from
 *       broadcast threads is always snapshot-safe.</li>
 *   <li>The ready set uses a concurrent {@link Set} backed by {@link ConcurrentHashMap}.</li>
 *   <li>{@link #setReady} and {@link #setUnready} are {@code synchronized} on {@code this}
 *       to make the all-ready check and countdown start/cancel atomic.</li>
 *   <li>A shared {@link ScheduledExecutorService} drives countdown timers for all
 *       rooms without spawning per-room threads.</li>
 * </ul>
 */
public class Room {

    private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(
        2, r -> { Thread t = new Thread(r, "RoomCountdown"); t.setDaemon(true); return t; }
    );

    private final String roomId;
    private final String name;
    private final String ownerSessionId;
    private final int    maxPlayers;

    private final CopyOnWriteArrayList<Player> players  = new CopyOnWriteArrayList<>();
    private final Set<String>                  readySet = ConcurrentHashMap.newKeySet();

    private volatile RoomState       state         = RoomState.WAITING;
    private volatile ScheduledFuture<?> countdownTask = null;

    /**
     * Constructs a new room.
     *
     * @param roomId          unique server-assigned identifier (e.g., {@code r-001})
     * @param name            display name chosen by the owner
     * @param ownerSessionId  session ID of the creating player
     * @param maxPlayers      maximum number of occupants; must be between 2 and 32
     */
    public Room(String roomId, String name, String ownerSessionId, int maxPlayers) {
        this.roomId         = roomId;
        this.name           = name;
        this.ownerSessionId = ownerSessionId;
        this.maxPlayers     = maxPlayers;
    }

    public String    getRoomId()        { return roomId; }
    public String    getName()          { return name; }
    public String    getOwnerSessionId(){ return ownerSessionId; }
    public int       getMaxPlayers()    { return maxPlayers; }
    public RoomState getState()         { return state; }
    public int       getPlayerCount()   { return players.size(); }
    public boolean   isFull()           { return players.size() >= maxPlayers; }
    public boolean   isReady(String sessionId) { return readySet.contains(sessionId); }

    /**
     * Returns an unmodifiable view of the current player list.
     *
     * @return live, snapshot-safe list of occupants
     */
    public List<Player> getPlayers() { return Collections.unmodifiableList(players); }

    /**
     * Attempts to add a player to this room.
     *
     * @param player the player to add
     * @return {@code true} if added; {@code false} if the room is full or not in WAITING state
     */
    public boolean addPlayer(Player player) {
        if (state != RoomState.WAITING || isFull()) { return false; }
        players.add(player);
        player.setCurrentRoomId(roomId);
        player.setStatus(PlayerStatus.IN_ROOM);
        return true;
    }

    /**
     * Removes a player from the room and handles downstream state changes.
     *
     * <p>If the room is in {@code READY_CHECK} when a player leaves, the countdown
     * is cancelled and the room reverts to {@code WAITING}. If the leaving player
     * is the owner, or if the room becomes empty, it is closed.
     *
     * @param player the player to remove
     */
    public void removePlayer(Player player) {
        players.remove(player);
        readySet.remove(player.getSessionId());
        player.setCurrentRoomId(null);
        player.setStatus(PlayerStatus.IN_LOBBY);

        if (state == RoomState.READY_CHECK) {
            cancelCountdown();
            state = RoomState.WAITING;
            broadcast("[READY] Ready check cancelled — a player left the room.");
        }

        if (players.isEmpty() || player.getSessionId().equals(ownerSessionId)) {
            close();
        }
    }

    /**
     * Marks a player as ready and triggers the countdown when all occupants are ready.
     *
     * @param player the player marking ready
     * @return {@code true} if the state allowed the operation; {@code false} otherwise
     */
    public synchronized boolean setReady(Player player) {
        if (state != RoomState.WAITING && state != RoomState.READY_CHECK) { return false; }
        readySet.add(player.getSessionId());
        broadcast("[READY] " + player.getLabel() + " is ready  (" + readySet.size() + "/" + players.size() + ")");

        if (readySet.size() == players.size() && players.size() > 1) {
            startCountdown();
        }
        return true;
    }

    /**
     * Removes a player's ready status and cancels any in-progress countdown.
     *
     * @param player the player marking unready
     * @return {@code true} if the state allowed the operation; {@code false} otherwise
     */
    public synchronized boolean setUnready(Player player) {
        if (state == RoomState.IN_PROGRESS || state == RoomState.CLOSED) { return false; }
        readySet.remove(player.getSessionId());

        if (state == RoomState.READY_CHECK) {
            cancelCountdown();
            state = RoomState.WAITING;
            broadcast("[READY] " + player.getLabel() + " is not ready — countdown cancelled.");
        } else {
            broadcast("[READY] " + player.getLabel() + " is not ready  (" + readySet.size() + "/" + players.size() + ")");
        }
        return true;
    }

    /**
     * Broadcasts a message to every player currently in this room.
     *
     * @param message text to deliver; a newline is appended by each handler
     */
    public void broadcast(String message) {
        for (Player p : players) { p.send(message); }
    }

    /**
     * Closes the room, clears all player assignments, and cancels any pending countdown.
     * Idempotent — subsequent calls after the first are no-ops.
     */
    public void close() {
        if (state == RoomState.CLOSED) { return; }
        state = RoomState.CLOSED;
        cancelCountdown();
        broadcast("[INFO] Room " + name + " has been closed.");
        for (Player p : new ArrayList<>(players)) {
            p.setCurrentRoomId(null);
            p.setStatus(PlayerStatus.IN_LOBBY);
        }
        players.clear();
        readySet.clear();
        Logger.logRegistry("Room closed: " + roomId + " '" + name + "'");
    }

    /**
     * Returns a formatted single-line summary suitable for the {@code LIST} command output.
     *
     * @return snapshot string in the form {@code r-001  Arena           2/4   WAITING}
     */
    public String getSnapshot() {
        return String.format("%-8s %-20s %d/%-4d %s", roomId, name, players.size(), maxPlayers, state);
    }

    private void startCountdown() {
        state = RoomState.READY_CHECK;
        broadcast("[READY] All players ready! Starting in 5...");

        for (int sec = 4; sec >= 1; sec--) {
            final int tick = sec;
            SCHEDULER.schedule(
                () -> { if (state == RoomState.READY_CHECK) broadcast("[READY] " + tick + "..."); },
                5 - tick, TimeUnit.SECONDS
            );
        }

        countdownTask = SCHEDULER.schedule(() -> {
            synchronized (Room.this) {
                if (state != RoomState.READY_CHECK) { return; }
                state = RoomState.IN_PROGRESS;
                for (Player p : players) { p.setStatus(PlayerStatus.IN_GAME); }
            }
            broadcast("[INFO] ⚔  Game session started! Good luck.");
            Logger.logRegistry("Room " + roomId + " transitioned to IN_PROGRESS.");
        }, 5, TimeUnit.SECONDS);
    }

    private void cancelCountdown() {
        if (countdownTask != null && !countdownTask.isDone()) {
            countdownTask.cancel(false);
            countdownTask = null;
        }
    }
}
