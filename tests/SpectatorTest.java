import room.Room;
import room.RoomConfig;
import room.RoomState;
import player.Player;
import player.PlayerStatus;

import java.util.List;

/**
 * Tests for the spectator mode subsystem.
 *
 * <p>Validates that:
 * <ul>
 *   <li>A spectator joins a room without consuming a player slot.</li>
 *   <li>Spectator receives broadcasts but cannot mutate room state.</li>
 *   <li>Spectator removal restores IN_LOBBY status.</li>
 *   <li>Spectating a CLOSED room is rejected.</li>
 * </ul>
 *
 * <p>Run directly:
 * <pre>
 *   javac -sourcepath src tests/SpectatorTest.java
 *   java -cp . SpectatorTest
 * </pre>
 */
public class SpectatorTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== SpectatorTest ===\n");

        testSpectatorJoinsWithoutConsumingSlot();
        testSpectatorReceivesBroadcast();
        testSpectatorRemovalRestoresLobbyStatus();
        testSpectatorRejectedOnClosedRoom();
        testSpectatorDoesNotCountInReadyCheck();

        System.out.println("\n--- Results ---");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        if (failed > 0) { System.exit(1); }
    }

    // -----------------------------------------------------------------------

    private static void testSpectatorJoinsWithoutConsumingSlot() {
        Room room = makeRoom("spectate-slot", 2);
        Player player = makePlayer("p1");
        Player spectator = makePlayer("spec1");
        player.setDisplayName("PlayerOne");
        spectator.setDisplayName("SpectatorOne");

        room.addPlayer(player);
        room.addSpectator(spectator);

        assertTrue("Player count remains 1 after spectator joins", room.getPlayerCount() == 1);
        assertTrue("Spectator list has 1 entry", room.getSpectators().size() == 1);
        assertTrue("Spectator status is SPECTATING",
            spectator.getStatus() == PlayerStatus.SPECTATING);
    }

    private static void testSpectatorReceivesBroadcast() {
        Room room = makeRoom("spectate-broadcast", 2);
        MessageRecorder playerRecorder = new MessageRecorder();
        MessageRecorder specRecorder   = new MessageRecorder();

        Player player    = makePlayerWithHandler("p-bcast",  playerRecorder);
        Player spectator = makePlayerWithHandler("s-bcast",  specRecorder);
        player.setDisplayName("PlayerBcast");
        spectator.setDisplayName("SpecBcast");

        room.addPlayer(player);
        room.addSpectator(spectator);
        room.broadcast("[TEST] broadcast message");

        assertTrue("Player receives broadcast", playerRecorder.received.stream()
            .anyMatch(m -> m.contains("[TEST]")));
        assertTrue("Spectator receives broadcast", specRecorder.received.stream()
            .anyMatch(m -> m.contains("[TEST]")));
    }

    private static void testSpectatorRemovalRestoresLobbyStatus() {
        Room room = makeRoom("spectate-remove", 2);
        Player spectator = makePlayer("spec-remove");
        spectator.setDisplayName("SpecRemove");

        room.addSpectator(spectator);
        assertTrue("Before removal: status is SPECTATING",
            spectator.getStatus() == PlayerStatus.SPECTATING);

        room.removeSpectator(spectator);
        assertTrue("After removal: status is IN_LOBBY",
            spectator.getStatus() == PlayerStatus.IN_LOBBY);
        assertTrue("Spectator list is empty after removal",
            room.getSpectators().isEmpty());
    }

    private static void testSpectatorRejectedOnClosedRoom() {
        Room room = makeRoom("spectate-closed", 2);
        room.close();
        Player spectator = makePlayer("spec-closed");
        spectator.setDisplayName("SpecClosed");

        boolean added = room.addSpectator(spectator);
        assertTrue("Spectator rejected by CLOSED room", !added);
    }

    private static void testSpectatorDoesNotCountInReadyCheck() {
        // Room with spectatorSlotsAllowed and minReadyCount=2
        RoomConfig cfg = new RoomConfig(null, 0, true, "standard", "ANY");
        Room room = new Room("r-spec-ready", "SpecReady", "owner", 4, cfg);

        Player p1 = makePlayer("p-ready-1");
        Player p2 = makePlayer("p-ready-2");
        Player sp = makePlayer("spec-ready");
        p1.setDisplayName("P1"); p2.setDisplayName("P2"); sp.setDisplayName("Spec");

        room.addPlayer(p1);
        room.addPlayer(p2);
        room.addSpectator(sp);

        assertTrue("Room state is still WAITING before ready", room.getState() == RoomState.WAITING);
        assertTrue("Spectator not in player count", room.getPlayerCount() == 2);
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

    private static Room makeRoom(String id, int maxPlayers) {
        return new Room(id, id + "-name", "owner", maxPlayers);
    }

    private static Player makePlayer(String sid) {
        return new Player(sid, null);
    }

    private static Player makePlayerWithHandler(String sid, server.ClientHandler h) {
        return new Player(sid, h);
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
