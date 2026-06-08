package persistence;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe event ledger tracking a sequence of events for a single room session.
 *
 * <p>Created when a room is registered, and finalized when the room closes.
 * Closing the ledger flushes its content to disk via {@link LedgerWriter}.
 */
public class SessionLedger {

    private final String roomId;
    private final CopyOnWriteArrayList<SessionEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Constructs a new SessionLedger for a specific room.
     *
     * @param roomId the unique room identifier
     */
    public SessionLedger(String roomId) {
        this.roomId = roomId;
    }

    /** Returns the room ID this ledger tracks. */
    public String getRoomId() {
        return roomId;
    }

    /**
     * Records an event in this ledger if the ledger is not closed.
     *
     * @param event the event to record
     */
    public void record(SessionEvent event) {
        if (!closed.get()) {
            events.add(event);
        }
    }

    /**
     * Returns an unmodifiable snapshot of the events recorded so far.
     *
     * @return unmodifiable list of SessionEvents
     */
    public List<SessionEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }

    /** Returns the number of events currently in the ledger. */
    public int size() {
        return events.size();
    }

    /**
     * Closes the ledger, preventing further writes and flushing its contents to disk.
     * Idempotent.
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            LedgerWriter.write(this);
        }
    }

    /** Returns true if this ledger is closed. */
    public boolean isClosed() {
        return closed.get();
    }
}
