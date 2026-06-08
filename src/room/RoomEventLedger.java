package room;

import core.Logger;

import java.io.*;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe, append-only event ledger for a single room session.
 *
 * <p>Records every meaningful state change in the room — joins, leaves, ready signals,
 * chat messages, countdown events, and match starts. When the room closes, call
 * {@link #writeToDisk()} to persist the full timeline for dispute resolution or replay.
 *
 * <p>Thread-safe: uses {@link CopyOnWriteArrayList} for iteration-safe appends.
 */
public final class RoomEventLedger {

    private static final String SESSIONS_DIR = "logs/rooms";

    private final String                      roomId;
    private final CopyOnWriteArrayList<RoomEvent> events = new CopyOnWriteArrayList<>();

    /**
     * Constructs a new ledger for the given room.
     *
     * @param roomId the room ID this ledger belongs to
     */
    public RoomEventLedger(String roomId) {
        this.roomId = roomId;
    }

    /**
     * Appends an event to the ledger.
     *
     * @param event the event to record
     */
    public void record(RoomEvent event) {
        events.add(event);
    }

    /**
     * Returns an unmodifiable snapshot of all recorded events.
     *
     * @return ordered event list
     */
    public List<RoomEvent> getAll() {
        return Collections.unmodifiableList(events);
    }

    /** Returns the number of events recorded so far. */
    public int size() { return events.size(); }

    /** Returns the room ID this ledger belongs to. */
    public String getRoomId() { return roomId; }

    /**
     * Prints all events to {@code out} with a T+ millisecond offset relative to the first event.
     *
     * @param out the print stream (e.g., {@link System#out})
     */
    public void printReplay(PrintStream out) {
        List<RoomEvent> snapshot = getAll();
        if (snapshot.isEmpty()) {
            out.println("[LEDGER] No events recorded for room " + roomId);
            return;
        }
        out.println("=== Session Replay: " + roomId + " (" + snapshot.size() + " events) ===");
        long baseMs = snapshot.get(0).timestamp().toEpochMilli();
        for (RoomEvent e : snapshot) {
            long offsetMs = e.timestamp().toEpochMilli() - baseMs;
            out.printf("T+%6dms  %s%n", offsetMs, e.toLogLine());
        }
    }

    /**
     * Writes the full event timeline to {@code logs/rooms/<roomId>.events}.
     * Creates the directory if absent. Silently logs any I/O errors.
     */
    public void writeToDisk() {
        File dir = new File(SESSIONS_DIR);
        dir.mkdirs();
        File file = new File(dir, roomId + ".events");
        try (PrintWriter w = new PrintWriter(new FileWriter(file, false))) {
            w.println("=== Room Ledger: " + roomId + " | Events: " + events.size() + " ===");
            for (RoomEvent e : events) {
                w.println(e.toLogLine());
            }
        } catch (IOException ex) {
            Logger.logServerError("RoomEventLedger: failed to write " + file + ": " + ex.getMessage());
        }
    }
}
