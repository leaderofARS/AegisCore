package protocol;

import core.Logger;
import matchmaking.MatchmakingQueue;
import player.Player;
import player.PlayerRegistry;
import player.PlayerStatus;
import room.Room;
import room.RoomRegistry;
import room.RoomState;

import java.util.List;

/**
 * Central command dispatcher for the AegisCore lobby protocol.
 *
 * <p>Every raw input line received from a client is passed to {@link #route(Player, String)},
 * which tokenises it, resolves the {@link CommandType}, validates preconditions (name
 * registration, player status, argument count), and delegates to the appropriate private
 * handler method.
 *
 * <p>A single {@code CommandRouter} instance is shared across all client-handler threads.
 * Handler methods are stateless with respect to {@code this}: all mutable state lives in
 * the injected registries and in the individual {@link Player} and {@link Room} objects,
 * which each provide their own thread-safety guarantees.
 *
 * <h3>Protocol</h3>
 * <pre>
 *   NAME   &lt;username&gt;              — register display name (mandatory first step)
 *   CREATE &lt;room-name&gt; [slots]     — create and join a new lobby room
 *   JOIN   &lt;room-id&gt;               — join an existing open room
 *   LEAVE                           — return to the lobby from a room
 *   LIST                            — list all open rooms
 *   READY                           — mark yourself ready in your current room
 *   UNREADY                         — cancel your ready status
 *   QUEUE                           — enter automatic matchmaking
 *   DEQUEUE                         — leave the matchmaking queue
 *   CHAT   &lt;message...&gt;            — broadcast a message inside your room
 *   STATS                           — print live server statistics
 *   QUIT                            — cleanly disconnect
 * </pre>
 */
public class CommandRouter {

    private final PlayerRegistry    playerRegistry;
    private final RoomRegistry      roomRegistry;
    private final MatchmakingQueue  matchmakingQueue;

    /**
     * Constructs a router with references to all subsystems it coordinates.
     *
     * @param playerRegistry    registry of connected players
     * @param roomRegistry      registry of active rooms
     * @param matchmakingQueue  automatic matchmaking service
     */
    public CommandRouter(PlayerRegistry playerRegistry,
                         RoomRegistry   roomRegistry,
                         MatchmakingQueue matchmakingQueue) {
        this.playerRegistry   = playerRegistry;
        this.roomRegistry     = roomRegistry;
        this.matchmakingQueue = matchmakingQueue;
    }

    /**
     * Parses and dispatches a raw input line received from the given player.
     *
     * <p>Empty or blank lines are silently ignored. The {@code NAME} and {@code QUIT}
     * commands are the only ones permitted before a display name is registered.
     *
     * @param player   the player who sent the input
     * @param rawInput the raw text line; may be blank but not {@code null}
     */
    public void route(Player player, String rawInput) {
        if (rawInput == null || rawInput.isBlank()) { return; }

        String[]    tokens = rawInput.trim().split("\\s+");
        CommandType type   = CommandType.parse(tokens[0]);
        String[]    args   = tokens.length > 1
                             ? java.util.Arrays.copyOfRange(tokens, 1, tokens.length)
                             : new String[0];

        CommandContext ctx = new CommandContext(player, type, args);
        Logger.logClientHandler("[CMD] " + player.getSessionId() + " → " + rawInput.trim());

        if (player.getDisplayName() == null && type != CommandType.NAME && type != CommandType.QUIT) {
            player.send("[ERROR] You must set a name first.  Usage: NAME <username>");
            return;
        }

        if (args.length < type.getMinArgs()) {
            player.send("[ERROR] Not enough arguments for " + type + ". Try: HELP");
            return;
        }

        switch (type) {
            case NAME    -> handleName(ctx);
            case CREATE  -> handleCreate(ctx);
            case JOIN    -> handleJoin(ctx);
            case LEAVE   -> handleLeave(ctx);
            case LIST    -> handleList(ctx);
            case READY   -> handleReady(ctx);
            case UNREADY -> handleUnready(ctx);
            case QUEUE   -> handleQueue(ctx);
            case DEQUEUE -> handleDequeue(ctx);
            case CHAT    -> handleChat(ctx);
            case STATS   -> handleStats(ctx);
            case QUIT    -> handleQuit(ctx);
            default      -> player.send("[ERROR] Unknown command. Valid: NAME CREATE JOIN LEAVE LIST READY UNREADY QUEUE DEQUEUE CHAT STATS QUIT");
        }
    }

    /**
     * Handles the disconnect cleanup for a player whose connection was closed unexpectedly.
     * Removes them from any room they occupied and from the matchmaking queue if applicable.
     *
     * @param player the disconnecting player
     */
    public void handleDisconnect(Player player) {
        if (player.getStatus() == PlayerStatus.QUEUED) {
            matchmakingQueue.dequeue(player);
        }
        String roomId = player.getCurrentRoomId();
        if (roomId != null) {
            Room room = roomRegistry.getRoom(roomId);
            if (room != null) {
                room.broadcast("[INFO] " + player.getLabel() + " lost their connection.");
                room.removePlayer(player);
                if (room.getState() == RoomState.CLOSED) {
                    roomRegistry.cleanupClosedRooms();
                }
            }
        }
    }

    private void handleName(CommandContext ctx) {
        String name = ctx.arg(0);
        if (name.length() < 2 || name.length() > 20) {
            ctx.player.send("[ERROR] Username must be 2–20 characters.");
            return;
        }
        ctx.player.setDisplayName(name);
        ctx.player.setStatus(PlayerStatus.IN_LOBBY);
        ctx.player.send("[SERVER] Welcome to AegisCore, " + name + "!");
        ctx.player.send("[SERVER] Commands: CREATE <name> [slots]  |  JOIN <id>  |  QUEUE  |  LIST  |  STATS");
        Logger.logClientHandler("Player named: " + name + " [" + ctx.player.getSessionId() + "]");
    }

    private void handleCreate(CommandContext ctx) {
        if (ctx.player.getStatus() != PlayerStatus.IN_LOBBY) {
            ctx.player.send("[ERROR] You must be in the lobby to create a room.");
            return;
        }
        String roomName  = ctx.arg(0);
        int    maxPlayers = 4;
        if (ctx.args.length >= 2) {
            try {
                maxPlayers = Integer.parseInt(ctx.args[1]);
                if (maxPlayers < 2 || maxPlayers > 32) {
                    ctx.player.send("[ERROR] Room slots must be between 2 and 32.");
                    return;
                }
            } catch (NumberFormatException e) {
                ctx.player.send("[ERROR] Slots must be a number.  Usage: CREATE <name> [slots]");
                return;
            }
        }
        Room room = roomRegistry.createRoom(roomName, ctx.player.getSessionId(), maxPlayers);
        room.addPlayer(ctx.player);
        ctx.player.send("[SERVER] Room created: " + room.getRoomId() + " | \"" + roomName + "\" | " + maxPlayers + " slots");
        ctx.player.send("[SERVER] Share this ID to invite others: " + room.getRoomId());
        ctx.player.send("[SERVER] Type READY when you are prepared to start.");
    }

    private void handleJoin(CommandContext ctx) {
        if (ctx.player.getStatus() != PlayerStatus.IN_LOBBY) {
            ctx.player.send("[ERROR] You must be in the lobby to join a room.");
            return;
        }
        String roomId = ctx.arg(0);
        Room   room   = roomRegistry.getRoom(roomId);
        if (room == null) {
            ctx.player.send("[ERROR] Room not found: " + roomId);
            return;
        }
        if (room.getState() != RoomState.WAITING) {
            ctx.player.send("[ERROR] Room " + roomId + " is not accepting players (state: " + room.getState() + ").");
            return;
        }
        if (room.isFull()) {
            ctx.player.send("[ERROR] Room " + roomId + " is full.");
            return;
        }
        room.addPlayer(ctx.player);
        room.broadcast("[INFO] " + ctx.player.getLabel() + " joined the room. (" + room.getPlayerCount() + "/" + room.getMaxPlayers() + ")");
        ctx.player.send("[SERVER] Joined room: " + room.getRoomId() + " | \"" + room.getName() + "\"");
        ctx.player.send("[SERVER] Type READY when you are prepared to start.");
    }

    private void handleLeave(CommandContext ctx) {
        String roomId = ctx.player.getCurrentRoomId();
        if (roomId == null) {
            ctx.player.send("[ERROR] You are not in a room.");
            return;
        }
        Room room = roomRegistry.getRoom(roomId);
        if (room != null) {
            room.broadcast("[INFO] " + ctx.player.getLabel() + " left the room.");
            room.removePlayer(ctx.player);
            if (room.getState() == RoomState.CLOSED) { roomRegistry.cleanupClosedRooms(); }
        }
        ctx.player.send("[SERVER] You left the room. You are back in the lobby.");
    }

    private void handleList(CommandContext ctx) {
        List<Room> open = roomRegistry.getOpenRooms();
        if (open.isEmpty()) {
            ctx.player.send("[SERVER] No open rooms. Create one with: CREATE <name> [slots]");
            return;
        }
        ctx.player.send("[SERVER] === Open Rooms ===================================");
        ctx.player.send(String.format("[SERVER]   %-8s %-20s %-8s %s", "ID", "Name", "Players", "Status"));
        for (Room r : open) {
            ctx.player.send("[SERVER]   " + r.getSnapshot());
        }
        ctx.player.send("[SERVER] =================================================");
    }

    private void handleReady(CommandContext ctx) {
        String roomId = ctx.player.getCurrentRoomId();
        if (roomId == null) {
            ctx.player.send("[ERROR] You must be in a room to mark yourself ready.");
            return;
        }
        Room room = roomRegistry.getRoom(roomId);
        if (room == null) { ctx.player.send("[ERROR] Room not found."); return; }
        if (room.isReady(ctx.player.getSessionId())) {
            ctx.player.send("[ERROR] You are already marked as ready.");
            return;
        }
        if (room.getPlayerCount() < 2) {
            ctx.player.send("[ERROR] At least 2 players must be in the room before you can ready up.");
            return;
        }
        room.setReady(ctx.player);
    }

    private void handleUnready(CommandContext ctx) {
        String roomId = ctx.player.getCurrentRoomId();
        if (roomId == null) { ctx.player.send("[ERROR] You are not in a room."); return; }
        Room room = roomRegistry.getRoom(roomId);
        if (room != null) { room.setUnready(ctx.player); }
    }

    private void handleQueue(CommandContext ctx) {
        if (ctx.player.getStatus() != PlayerStatus.IN_LOBBY) {
            ctx.player.send("[ERROR] You must be in the lobby to enter matchmaking.");
            return;
        }
        boolean queued = matchmakingQueue.enqueue(ctx.player);
        if (queued) {
            ctx.player.send("[MATCH] * You entered the matchmaking queue. Position: ~" + matchmakingQueue.getQueueSize());
            ctx.player.send("[MATCH]   Type DEQUEUE to leave the queue.");
        }
    }

    private void handleDequeue(CommandContext ctx) {
        if (ctx.player.getStatus() != PlayerStatus.QUEUED) {
            ctx.player.send("[ERROR] You are not in the matchmaking queue.");
            return;
        }
        matchmakingQueue.dequeue(ctx.player);
        ctx.player.send("[MATCH] You have left the matchmaking queue.");
    }

    private void handleChat(CommandContext ctx) {
        String roomId = ctx.player.getCurrentRoomId();
        if (roomId == null) {
            ctx.player.send("[ERROR] You must be in a room to use CHAT.");
            return;
        }
        Room room = roomRegistry.getRoom(roomId);
        if (room != null) {
            room.broadcast("[ROOM] " + ctx.player.getLabel() + ": " + ctx.joinArgs(0));
        }
    }

    private void handleStats(CommandContext ctx) {
        ctx.player.send("[SERVER] === AegisCore Stats ===========================");
        ctx.player.send("[SERVER]   Players online : " + playerRegistry.getPlayerCount());
        ctx.player.send("[SERVER]   Active rooms   : " + roomRegistry.getActiveRoomCount());
        ctx.player.send("[SERVER]   Queue size     : " + matchmakingQueue.getQueueSize());
        ctx.player.send("[SERVER]   Total connects : " + playerRegistry.getTotalConnections());
        ctx.player.send("[SERVER] =================================================");
    }

    private void handleQuit(CommandContext ctx) {
        ctx.player.send("[SERVER] Disconnecting. Farewell, " + ctx.player.getLabel() + ".");
        ctx.player.getHandler().forceDisconnect();
    }
}
