package social;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe singleton holding the recent private message histories.
 *
 * <p>Restricts storage to the last 50 messages per player session.
 */
public final class WhisperHistory {

    /**
     * An individual private message entry in the history.
     */
    public record Entry(String from, String to, String message, Instant timestamp) {
        @Override
        public String toString() {
            return String.format("[%s] %s -> %s: %s", timestamp, from, to, message);
        }
    }

    private static final WhisperHistory INSTANCE = new WhisperHistory();

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Entry>> histories = new ConcurrentHashMap<>();

    private WhisperHistory() {}

    /** Returns the singleton {@code WhisperHistory} instance. */
    public static WhisperHistory getInstance() {
        return INSTANCE;
    }

    /**
     * Records a whisper. Add to the history of both the sender and the receiver.
     *
     * @param fromSession the sender's session ID
     * @param toSession   the receiver's session ID
     * @param fromName    the sender's display name
     * @param toName      the receiver's display name
     * @param message     the whisper text
     */
    public void record(String fromSession, String toSession, String fromName, String toName, String message) {
        Entry entry = new Entry(fromName, toName, message, Instant.now());
        recordForSession(fromSession, entry);
        recordForSession(toSession, entry);
    }

    private void recordForSession(String sessionId, Entry entry) {
        histories.compute(sessionId, (k, v) -> {
            if (v == null) {
                v = new CopyOnWriteArrayList<>();
            }
            v.add(entry);
            while (v.size() > 50) {
                v.remove(0);
            }
            return v;
        });
    }

    /**
     * Retrieves the unmodifiable list of private messages for a player session.
     *
     * @param sessionId the player session ID
     * @return list of entries
     */
    public List<Entry> getHistory(String sessionId) {
        List<Entry> history = histories.get(sessionId);
        if (history == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    /** Clears history for a player session. */
    public void clear(String sessionId) {
        histories.remove(sessionId);
    }
}
