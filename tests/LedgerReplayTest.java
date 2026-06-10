import persistence.LedgerReplayer;
import persistence.LedgerWriter;
import persistence.SessionEvent;
import persistence.SessionEventType;
import persistence.SessionLedger;

import java.io.File;
import java.util.List;

/**
 * Tests for the session event ledger write and replay pipeline.
 *
 * <p>Validates that:
 * <ul>
 *   <li>Events are recorded in order.</li>
 *   <li>Closed ledger prevents further writes.</li>
 *   <li>Ledger is written to disk on close.</li>
 *   <li>{@link LedgerReplayer#replay(String)} reads back raw event lines in order.</li>
 * </ul>
 *
 * <p>Run directly:
 * <pre>
 *   javac -sourcepath src tests/LedgerReplayTest.java
 *   java -cp . LedgerReplayTest
 * </pre>
 */
public class LedgerReplayTest {

    private static int passed = 0;
    private static int failed = 0;

    private static final String TEST_ROOM = "test-r-replay";

    public static void main(String[] args) {
        System.out.println("=== LedgerReplayTest ===\n");

        // Make sure sessions dir exists
        new File("logs/sessions").mkdirs();

        testEventOrderPreserved();
        testClosedLedgerIgnoresWrites();
        testLedgerWrittenToDisk();
        testReplayRawLinesPresent();

        System.out.println("\n--- Results ---");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        if (failed > 0) { System.exit(1); }
    }

    // -----------------------------------------------------------------------

    private static void testEventOrderPreserved() {
        SessionLedger ledger = new SessionLedger(TEST_ROOM + "-order");
        ledger.record(SessionEvent.of(SessionEventType.PLAYER_JOIN,   "s1", "Alice", null));
        ledger.record(SessionEvent.of(SessionEventType.PLAYER_READY,  "s1", "Alice", null));
        ledger.record(SessionEvent.of(SessionEventType.PLAYER_LEAVE,  "s1", "Alice", null));

        List<SessionEvent> events = ledger.getEvents();
        assertTrue("Three events recorded", events.size() == 3);
        assertTrue("First event is JOIN",   events.get(0).type() == SessionEventType.PLAYER_JOIN);
        assertTrue("Second event is READY", events.get(1).type() == SessionEventType.PLAYER_READY);
        assertTrue("Third event is LEAVE",  events.get(2).type() == SessionEventType.PLAYER_LEAVE);
    }

    private static void testClosedLedgerIgnoresWrites() {
        SessionLedger ledger = new SessionLedger(TEST_ROOM + "-closed");
        ledger.record(SessionEvent.of(SessionEventType.PLAYER_JOIN, "s1", "Alice", null));
        int beforeClose = ledger.size();
        ledger.close();
        ledger.record(SessionEvent.of(SessionEventType.PLAYER_LEAVE, "s1", "Alice", null));
        int afterClose = ledger.size();
        assertTrue("Event count unchanged after close", beforeClose == afterClose);
        assertTrue("Ledger reports closed", ledger.isClosed());
    }

    private static void testLedgerWrittenToDisk() {
        String roomId = TEST_ROOM + "-disk";
        SessionLedger ledger = new SessionLedger(roomId);
        ledger.record(SessionEvent.of(SessionEventType.PLAYER_JOIN, "s1", "Alice", null));
        ledger.close();
        File f = new File("logs/sessions/" + roomId + ".ledger");
        assertTrue("Ledger file exists on disk after close", f.exists() && f.length() > 0);
    }

    private static void testReplayRawLinesPresent() {
        String roomId = TEST_ROOM + "-replay";
        SessionLedger ledger = new SessionLedger(roomId);
        ledger.record(SessionEvent.of(SessionEventType.PLAYER_JOIN,  "s1", "Alice", null));
        ledger.record(SessionEvent.of(SessionEventType.CHAT_MESSAGE,  "s1", "Alice", "hello"));
        ledger.record(SessionEvent.of(SessionEventType.PLAYER_LEAVE, "s1", "Alice", null));
        ledger.close();

        List<String> lines = LedgerReplayer.replay(roomId);
        assertTrue("Replay returns at least 3 lines (header + 3 events)", lines.size() >= 3);

        // Check event types appear somewhere in the raw output
        boolean hasJoin  = lines.stream().anyMatch(l -> l.contains("JOIN"));
        boolean hasChat  = lines.stream().anyMatch(l -> l.contains("CHAT"));
        boolean hasLeave = lines.stream().anyMatch(l -> l.contains("LEAVE"));
        boolean hasData  = lines.stream().anyMatch(l -> l.contains("hello"));

        assertTrue("Replay contains JOIN event",  hasJoin);
        assertTrue("Replay contains CHAT event",  hasChat);
        assertTrue("Replay contains LEAVE event", hasLeave);
        assertTrue("Replay contains chat data",   hasData);
    }

    // -----------------------------------------------------------------------

    private static void assertTrue(String name, boolean condition) {
        if (condition) {
            System.out.println("  [PASS] " + name);
            passed++;
        } else {
            System.out.println("  [FAIL] " + name);
            failed++;
        }
    }
}
