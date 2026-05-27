package matchmaking;

import player.Player;
import player.PlayerStatus;
import room.Room;
import room.RoomRegistry;
import core.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Background matchmaking service that automatically groups queued players into rooms.
 *
 * <p>Runs as a dedicated daemon thread. Blocks on a {@link LinkedBlockingQueue} until
 * enough players have entered the queue, then atomically dequeues them and creates a
 * lobby room via {@link RoomRegistry}. If the queue does not fill within
 * {@value #QUEUE_TIMEOUT_SECONDS} seconds after the first player joins, all collected
 * players are released back to the lobby with a timeout notification.
 *
 * <h3>Thread safety</h3>
 * {@link LinkedBlockingQueue} is thread-safe. {@link #enqueue} and {@link #dequeue} may
 * be called concurrently from any client-handler thread. The run loop executes on a single
 * dedicated thread — no additional synchronisation is needed inside {@link #run}.
 */
public class MatchmakingQueue implements Runnable {

    private static final int QUEUE_TIMEOUT_SECONDS = 30;

    private final LinkedBlockingQueue<Player> queue;
    private final MatchConfig                 config;
    private final RoomRegistry                roomRegistry;
    private volatile boolean                  running = true;

    /**
     * Constructs a new matchmaking queue.
     *
     * @param config       match size configuration
     * @param roomRegistry registry used to create rooms when a match is found
     */
    public MatchmakingQueue(MatchConfig config, RoomRegistry roomRegistry) {
        this.config       = config;
        this.roomRegistry = roomRegistry;
        this.queue        = new LinkedBlockingQueue<>();
    }

    /**
     * Adds a player to the matchmaking queue if they are currently in the lobby.
     *
     * @param player the player to enqueue
     * @return {@code true} if enqueued; {@code false} if the player was not in IN_LOBBY state
     */
    public boolean enqueue(Player player) {
        if (player.getStatus() != PlayerStatus.IN_LOBBY) { return false; }
        player.setStatus(PlayerStatus.QUEUED);
        queue.add(player);
        Logger.logRegistry("Player queued: " + player.getLabel() + " | Queue size: " + queue.size());
        return true;
    }

    /**
     * Removes a player from the queue and returns them to the lobby.
     *
     * @param player the player to dequeue
     * @return {@code true} if removed; {@code false} if not found in the queue
     */
    public boolean dequeue(Player player) {
        boolean removed = queue.remove(player);
        if (removed) {
            player.setStatus(PlayerStatus.IN_LOBBY);
            Logger.logRegistry("Player dequeued: " + player.getLabel() + " | Queue size: " + queue.size());
        }
        return removed;
    }

    /** Returns the current number of players waiting in the queue. */
    public int getQueueSize() { return queue.size(); }

    /** Signals the run loop to exit on its next iteration. */
    public void stop() { running = false; }

    /**
     * Main matchmaking loop. Blocks until a player enters the queue, then
     * collects additional players until the match size is reached or the
     * timeout expires. Matched players are placed into a new room.
     */
    @Override
    public void run() {
        while (running) {
            try {
                int          needed  = config.getPlayersPerMatch();
                List<Player> matched = new ArrayList<>(needed);

                Player first = queue.take();
                if (first.getStatus() != PlayerStatus.QUEUED) { continue; }
                matched.add(first);

                while (matched.size() < needed) {
                    Player next = queue.poll(QUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (next == null) {
                        for (Player p : matched) {
                            p.setStatus(PlayerStatus.IN_LOBBY);
                            p.send("[MATCH] Matchmaking timed out — no opponents found. Returning to lobby.");
                        }
                        matched.clear();
                        break;
                    }
                    if (next.getStatus() == PlayerStatus.QUEUED) {
                        matched.add(next);
                    }
                }

                if (matched.size() == needed) {
                    createMatch(matched);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    private void createMatch(List<Player> players) {
        Room room = roomRegistry.createRoom(
            "Match-" + System.currentTimeMillis(),
            players.get(0).getSessionId(),
            players.size()
        );
        for (Player p : players) {
            room.addPlayer(p);
            p.send("[MATCH] ✦ Match found! You have been placed in room: " + room.getRoomId());
        }
        room.broadcast("[MATCH] Match created with " + players.size() +
                       " players. Type READY when you are prepared to enter the session.");
        Logger.logRegistry("Match created: " + room.getRoomId() + " | Players: " + players.size());
    }
}
