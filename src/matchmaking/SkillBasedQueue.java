package matchmaking;

import player.Player;
import player.PlayerStatus;
import core.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Skill-sorted matchmaking queue for AegisCore.
 *
 * <p>Players are enqueued with a priority weight derived from their MMR rating.
 * The queue uses a {@link PriorityBlockingQueue} sorted by MMR so that players
 * with similar ratings float together and are more likely to meet the
 * {@link MatchmakingPolicy#SKILL_BASED} compatibility check.
 *
 * <p>This class is a drop-in replacement for the standard {@link MatchmakingQueue}
 * where higher match quality is preferred over queue fill speed.
 *
 * <h3>Thread safety</h3>
 * {@link PriorityBlockingQueue} is thread-safe. The run loop executes on a single
 * dedicated thread — no additional synchronisation is needed inside {@link #run}.
 */
public final class SkillBasedQueue implements Runnable {

    private static final int QUEUE_TIMEOUT_SECONDS = 30;

    private final MatchConfig        config;
    private final MatchmakingPolicy  policy;
    private final room.RoomRegistry  roomRegistry;
    private volatile boolean         running = true;

    /**
     * Priority queue ordered by ascending MMR — lowest-rated players dequeue first.
     * This tends to cluster similar-rated players when skill deltas are checked.
     */
    private final PriorityBlockingQueue<Player> queue =
        new PriorityBlockingQueue<>(16, Comparator.comparingInt(
            p -> PlayerSkillProfile.getOrCreate(p.getSessionId()).getRating()
        ));

    /**
     * Constructs a skill-based queue with the given configuration and policy.
     *
     * @param config       match configuration (player count, delta threshold, region)
     * @param roomRegistry registry used to create rooms when a match is found
     * @param policy       compatibility policy applied during player pairing
     */
    public SkillBasedQueue(MatchConfig config, room.RoomRegistry roomRegistry,
                           MatchmakingPolicy policy) {
        this.config       = config;
        this.roomRegistry = roomRegistry;
        this.policy       = policy;
    }

    /**
     * Enqueues a player if they are currently in the lobby.
     *
     * @param player the player to enqueue
     * @return {@code true} if enqueued; {@code false} if status was not {@code IN_LOBBY}
     */
    public boolean enqueue(Player player) {
        if (player.getStatus() != PlayerStatus.IN_LOBBY) return false;
        player.setStatus(PlayerStatus.QUEUED);
        queue.add(player);
        Logger.logRegistry("[SkillQ] Enqueued " + player.getLabel() +
            " (MMR=" + PlayerSkillProfile.getOrCreate(player.getSessionId()).getRating() +
            ") | Size=" + queue.size());
        return true;
    }

    /**
     * Removes a player from the queue and returns them to the lobby.
     *
     * @param player the player to dequeue
     * @return {@code true} if the player was removed
     */
    public boolean dequeue(Player player) {
        boolean removed = queue.remove(player);
        if (removed) {
            player.setStatus(PlayerStatus.IN_LOBBY);
            Logger.logRegistry("[SkillQ] Dequeued " + player.getLabel());
        }
        return removed;
    }

    /** Returns the number of players currently waiting in this queue. */
    public int getQueueSize() { return queue.size(); }

    /** Signals the run loop to stop after its current iteration. */
    public void stop() { running = false; }

    /**
     * Main matchmaking loop. Blocks until a player enters the queue, then
     * builds a compatible group using the configured policy, and creates a match.
     */
    @Override
    public void run() {
        while (running) {
            try {
                int          needed  = config.getPlayersPerMatch();
                List<Player> matched = new ArrayList<>(needed);
                List<Player> skipped = new ArrayList<>();

                // Block for the first player
                Player first;
                do {
                    first = queue.poll(QUEUE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    if (first == null || !running) break;
                } while (first.getStatus() != PlayerStatus.QUEUED);

                if (first == null || !running) continue;
                matched.add(first);

                long deadline = System.currentTimeMillis() + config.timeoutSeconds() * 1000L;

                // Collect remaining players using compatibility check
                outer:
                while (matched.size() < needed && System.currentTimeMillis() < deadline) {
                    Player next = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (next == null) continue;
                    if (next.getStatus() != PlayerStatus.QUEUED) continue;

                    if (policy.isCompatible(next, matched, config)) {
                        matched.add(next);
                    } else {
                        skipped.add(next); // return incompatible players later
                    }
                }

                // Return skipped players to the queue
                for (Player p : skipped) { queue.offer(p); }

                if (matched.size() == needed) {
                    createMatch(matched);
                } else {
                    // Timeout — return all to lobby
                    for (Player p : matched) {
                        p.setStatus(PlayerStatus.IN_LOBBY);
                        p.send("[MATCH] Skill-matched queue timed out — returning to lobby.");
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    // -----------------------------------------------------------------------

    private void createMatch(List<Player> players) {
        room.Room room = roomRegistry.createRoom(
            "SkillMatch-" + System.currentTimeMillis(),
            players.get(0).getSessionId(),
            players.size()
        );

        // Record match history for re-match prevention
        for (Player p : players) {
            PlayerSkillProfile pp = PlayerSkillProfile.getOrCreate(p.getSessionId());
            for (Player other : players) {
                if (other != p) pp.recordMatch(other.getSessionId());
            }
            room.addPlayer(p);
            p.send("[MATCH] Skill match found! Room: " + room.getRoomId() +
                   " | Your MMR: " + pp.getRating() + " | Bracket: " + pp.getBracket().name());
        }

        room.broadcast("[MATCH] Skill-balanced match started with " + players.size() +
                       " players. Type READY to begin.");
        Logger.logRegistry("[SkillQ] Match created: " + room.getRoomId() + " | Policy: " + policy.name());
    }
}
