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
    private volatile String ownerSessionId;
    private final int    maxPlayers;
    private final RoomConfig config;
    private final RoomEventLedger eventLedger;

    private final CopyOnWriteArrayList<Player> players  = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Player> spectators = new CopyOnWriteArrayList<>();
    private final Set<String>                  readySet = ConcurrentHashMap.newKeySet();

    private volatile RoomState       state         = RoomState.WAITING;
    private volatile ScheduledFuture<?> countdownTask = null;

    /**
     * Constructs a new room with a default configuration.
     *
     * @param roomId          unique server-assigned identifier (e.g., {@code r-001})
     * @param name            display name chosen by the owner
     * @param ownerSessionId  session ID of the creating player
     * @param maxPlayers      maximum number of occupants; must be between 2 and 32
     */
    public Room(String roomId, String name, String ownerSessionId, int maxPlayers) {
        this(roomId, name, ownerSessionId, maxPlayers, RoomConfig.defaultConfig());
    }

    /**
     * Constructs a new room with a custom configuration.
     *
     * @param roomId          unique server-assigned identifier
     * @param name            display name chosen by the owner
     * @param ownerSessionId  session ID of the creating player
     * @param maxPlayers      maximum number of occupants
     * @param config          custom room configuration
     */
    public Room(String roomId, String name, String ownerSessionId, int maxPlayers, RoomConfig config) {
        this.roomId         = roomId;
        this.name           = name;
        this.ownerSessionId = ownerSessionId;
        this.maxPlayers     = maxPlayers;
        this.config         = config;
        this.eventLedger    = new RoomEventLedger(roomId);
        this.eventLedger.record(RoomEvent.system(RoomEventType.ROOM_CREATED, "Room " + name + " created by owner " + ownerSessionId));
    }

    public String    getRoomId()        { return roomId; }
    public String    getName()          { return name; }
    public String    getOwnerSessionId(){ return ownerSessionId; }
    public void      setOwnerSessionId(String ownerSessionId) { this.ownerSessionId = ownerSessionId; }
    public int       getMaxPlayers()    { return maxPlayers; }
    public RoomState getState()         { return state; }
    public RoomConfig getConfig()       { return config; }
    public RoomEventLedger getEventLedger() { return eventLedger; }
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
     * Returns an unmodifiable view of the current spectator list.
     *
     * @return live, snapshot-safe list of spectators
     */
    public List<Player> getSpectators() { return Collections.unmodifiableList(spectators); }

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
        eventLedger.record(RoomEvent.of(RoomEventType.PLAYER_JOINED, player.getSessionId(), player.getDisplayName(), "Player joined"));
        return true;
    }

    /**
     * Removes a player from the room and handles downstream state changes.
     *
     * <p>If the room is in {@code READY_CHECK} when a player leaves, the countdown
     * is cancelled and the room reverts to {@code WAITING}. If the leaving player
     * is the owner, or if the room becomes empty, it is closed or owner is transferred.
     *
     * @param player the player to remove
     */
    public void removePlayer(Player player) {
        players.remove(player);
        readySet.remove(player.getSessionId());
        player.setCurrentRoomId(null);
        player.setStatus(PlayerStatus.IN_LOBBY);
        eventLedger.record(RoomEvent.of(RoomEventType.PLAYER_LEFT, player.getSessionId(), player.getDisplayName(), "Player left"));

        if (state == RoomState.READY_CHECK) {
            cancelCountdown();
            state = RoomState.WAITING;
            eventLedger.record(RoomEvent.system(RoomEventType.COUNTDOWN_CANCELLED, "Ready check cancelled — player left"));
            broadcast("[READY] Ready check cancelled — a player left the room.");
        }

        if (players.isEmpty()) {
            close();
        } else if (player.getSessionId().equals(ownerSessionId)) {
            Player nextOwner = players.get(0);
            ownerSessionId = nextOwner.getSessionId();
            eventLedger.record(RoomEvent.of(RoomEventType.OWNER_TRANSFERRED, nextOwner.getSessionId(), nextOwner.getDisplayName(), "Owner transferred to " + nextOwner.getLabel()));
            broadcast("[ROOM] Owner left. " + nextOwner.getLabel() + " is now the room owner.");
        }
    }

    /**
     * Attempts to add a spectator to this room.
     *
     * @param spectator the spectator to add
     * @return {@code true} if added; {@code false} if the room is closed
     */
    public boolean addSpectator(Player spectator) {
        if (state == RoomState.CLOSED) { return false; }
        spectators.add(spectator);
        spectator.setCurrentRoomId(roomId);
        spectator.setStatus(PlayerStatus.SPECTATING);
        eventLedger.record(RoomEvent.of(RoomEventType.SPECTATOR_JOINED, spectator.getSessionId(), spectator.getDisplayName(), "Spectator joined"));
        return true;
    }

    /**
     * Removes a spectator from the room.
     *
     * @param spectator the spectator to remove
     */
    public void removeSpectator(Player spectator) {
        spectators.remove(spectator);
        spectator.setCurrentRoomId(null);
        spectator.setStatus(PlayerStatus.IN_LOBBY);
        eventLedger.record(RoomEvent.of(RoomEventType.SPECTATOR_LEFT, spectator.getSessionId(), spectator.getDisplayName(), "Spectator left"));
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
        eventLedger.record(RoomEvent.of(RoomEventType.PLAYER_READY, player.getSessionId(), player.getDisplayName(), "Player ready"));
        broadcast("[READY] " + player.getLabel() + " is ready  (" + readySet.size() + "/" + players.size() + ")");

        int minReady = config.minReadyCount() > 0 ? config.minReadyCount() : players.size();
        if (readySet.size() >= minReady && players.size() > 1) {
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
        eventLedger.record(RoomEvent.of(RoomEventType.PLAYER_UNREADY, player.getSessionId(), player.getDisplayName(), "Player unready"));

        if (state == RoomState.READY_CHECK) {
            cancelCountdown();
            state = RoomState.WAITING;
            eventLedger.record(RoomEvent.system(RoomEventType.COUNTDOWN_CANCELLED, "Ready check cancelled — player unready"));
            broadcast("[READY] " + player.getLabel() + " is not ready — countdown cancelled.");
        } else {
            broadcast("[READY] " + player.getLabel() + " is not ready  (" + readySet.size() + "/" + players.size() + ")");
        }
        return true;
    }

    /**
     * Broadcasts a message to every player and spectator currently in this room.
     *
     * @param message text to deliver; a newline is appended by each handler
     */
    public void broadcast(String message) {
        for (Player p : players) { p.send(message); }
        for (Player p : spectators) { p.send(message); }
    }

    /**
     * Records a chat message in the event ledger and broadcasts it.
     */
    public void recordChat(Player sender, String message) {
        eventLedger.record(RoomEvent.of(RoomEventType.CHAT_MESSAGE, sender.getSessionId(), sender.getDisplayName(), message));
    }

    /**
     * Closes the room, clears all player assignments, and cancels any pending countdown.
     * Idempotent — subsequent calls after the first are no-ops.
     */
    public void close() {
        if (state == RoomState.CLOSED) { return; }
        state = RoomState.CLOSED;
        cancelCountdown();
        eventLedger.record(RoomEvent.system(RoomEventType.ROOM_CLOSED, "Room closed"));
        broadcast("[INFO] Room " + name + " has been closed.");
        for (Player p : new ArrayList<>(players)) {
            p.setCurrentRoomId(null);
            p.setStatus(PlayerStatus.IN_LOBBY);
        }
        for (Player p : new ArrayList<>(spectators)) {
            p.setCurrentRoomId(null);
            p.setStatus(PlayerStatus.IN_LOBBY);
        }
        players.clear();
        spectators.clear();
        readySet.clear();
        eventLedger.writeToDisk();
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
        eventLedger.record(RoomEvent.system(RoomEventType.COUNTDOWN_STARTED, "Countdown started"));
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
                eventLedger.record(RoomEvent.system(RoomEventType.MATCH_STARTED, "Match started"));
            }
            broadcast("[INFO] Game session started! Good luck.");
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
