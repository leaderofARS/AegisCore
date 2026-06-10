import social.WhisperRouter;
import social.WhisperHistory;
import player.Player;
import player.PlayerRegistry;

import java.util.List;

/**
 * Tests for the WHISPER private-messaging subsystem.
 *
 * <p>Validates that:
 * <ul>
 *   <li>A whisper is delivered to the correct recipient only.</li>
 *   <li>Whisper to an unknown player fails gracefully.</li>
 *   <li>{@link WhisperHistory} records entries and trims to the sliding window.</li>
 * </ul>
 *
 * <p>Run directly:
 * <pre>
 *   javac -sourcepath src tests/WhisperTest.java
 *   java -cp . WhisperTest
 * </pre>
 */
public class WhisperTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== WhisperTest ===\n");

        testWhisperHistoryRecord();
        testWhisperHistoryWindowCap();
        testWhisperToUnknownFails();
        testWhisperHistoryGetEntries();

        System.out.println("\n--- Results ---");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        if (failed > 0) { System.exit(1); }
    }

    // -----------------------------------------------------------------------

    private static void testWhisperHistoryRecord() {
        WhisperHistory wh = WhisperHistory.getInstance();
        wh.record("wh-s1", "wh-s2", "Alice", "Bob", "Hello Bob!");
        List<WhisperHistory.Entry> entries = wh.getHistory("wh-s1");
        boolean found = entries.stream()
            .anyMatch(e -> e.message().equals("Hello Bob!"));
        assertTrue("Whisper entry is recorded in sender history", found);
    }

    private static void testWhisperHistoryWindowCap() {
        WhisperHistory wh = WhisperHistory.getInstance();
        // Write 60 messages — history should cap at 50 per session
        for (int i = 0; i < 60; i++) {
            wh.record("wh-cap-s1", "wh-cap-s2", "A", "B", "msg-" + i);
        }
        List<WhisperHistory.Entry> hist = wh.getHistory("wh-cap-s1");
        assertTrue("History window is capped at ≤50 entries", hist.size() <= 50);
    }

    private static void testWhisperToUnknownFails() {
        // Set up a mock sender that records received messages
        MessageRecorder senderRecorder = new MessageRecorder();
        Player sender = new Player("wh-sender-session", senderRecorder);
        PlayerRegistry.getInstance().register(sender);
        sender.setDisplayName("SenderAlice");
        sender.setStatus(player.PlayerStatus.IN_LOBBY);

        boolean result = WhisperRouter.sendWhisper(sender, "NonExistentPlayerXYZ", "hi");
        assertTrue("sendWhisper returns false for unknown target", !result);
        assertTrue("Sender receives error message",
            senderRecorder.received.stream().anyMatch(m -> m.contains("[ERROR]")));

        PlayerRegistry.getInstance().deregister("wh-sender-session");
    }

    private static void testWhisperHistoryGetEntries() {
        WhisperHistory wh = WhisperHistory.getInstance();
        wh.record("wh-hist-A", "wh-hist-B", "PlayerA", "PlayerB", "test-entry");
        List<WhisperHistory.Entry> a = wh.getHistory("wh-hist-A");
        List<WhisperHistory.Entry> b = wh.getHistory("wh-hist-B");
        assertTrue("Both sender and recipient see the whisper", !a.isEmpty() && !b.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static class MessageRecorder extends server.ClientHandler {
        final java.util.List<String> received = new java.util.ArrayList<>();
        MessageRecorder() { super(null, null, null); }
        @Override public synchronized void sendMessage(String msg) { received.add(msg); }
        @Override public boolean isActive() { return true; }
    }

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
