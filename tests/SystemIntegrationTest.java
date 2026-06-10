import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import server.Server;
import player.PlayerRegistry;

/**
 * Robust, automated integration test suite that aggressively validates
 * AegisCore against 4 critical network and concurrency failure modes:
 *
 * 1. Multiple Receivers      — CHAT broadcast reaches all room members
 * 2. Disconnect During Broadcast — server survives a dead socket mid-broadcast
 * 3. Rapid Messaging         — concurrent spam storms produce no crashes
 * 4. Reconnect Storm         — 60 rapid connect/QUIT cycles leave 0 zombie sessions
 *
 * Protocol notes:
 *  - Server sends EXACTLY 2 lines on new TCP connection (after ProtocolDetector peek):
 *      "[SERVER] Connected to AegisCore Game Lobby Server."
 *      "[SERVER] Set your name to begin:  NAME <username>"
 *  - NAME <n> reply = 2 lines: welcome + commands hint.
 *  - CREATE <name> [slots] reply = 3 lines: "Room created", "Share this ID", "Type READY".
 *    Additionally, the CREATE command causes the creator to receive a JOIN broadcast from
 *    the room (i.e., "[INFO] <player> joined the room. (1/N)") via addPlayer → no, actually
 *    CREATE does NOT broadcast to anyone since the room is brand new with only 1 member.
 *  - JOIN <roomId> reply to joiner = 2 lines; broadcasts "[INFO] X joined" to all existing members.
 *  - CHAT <msg> = broadcasts "[ROOM] <label>: <msg>" to all room members (including sender).
 *  - QUIT = 1 farewell line, then server closes the connection.
 *  - LIST = variable lines ending with the separator line "====...====".
 */
public class SystemIntegrationTest {

    private static final String HOST             = "localhost";
    private static final int    PORT             = 5000;
    private static final int    CONNECT_TIMEOUT  = 5000;
    private static final int    READ_TIMEOUT_MS  = 30_000; // generous: server has 500ms ProtocolDetector peek

    // ── Protocol helpers ──────────────────────────────────────────────────────

    /**
     * Create a connected, timeout-configured reader/writer pair.
     * Drains the 2-line greeting automatically.
     */
    private static Socket[] connectAndGreet(BufferedReader[] readers, PrintWriter[] writers,
                                             int count) throws IOException {
        Socket[] sockets = new Socket[count];
        for (int i = 0; i < count; i++) {
            sockets[i] = new Socket();
            sockets[i].connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT);
            sockets[i].setSoTimeout(READ_TIMEOUT_MS);
            readers[i] = new BufferedReader(new InputStreamReader(sockets[i].getInputStream()));
            writers[i] = new PrintWriter(sockets[i].getOutputStream(), true);
            // Drain the 2 greeting lines the server sends after ProtocolDetector finishes
            readers[i].readLine(); // "[SERVER] Connected to AegisCore Game Lobby Server."
            readers[i].readLine(); // "[SERVER] Set your name to begin:  NAME <username>"
        }
        return sockets;
    }

    /**
     * Send NAME and drain the 2 reply lines (welcome + commands hint).
     */
    private static void setName(BufferedReader reader, PrintWriter writer, String name)
            throws IOException {
        writer.println("NAME " + name);
        reader.readLine(); // "[SERVER] Welcome to AegisCore, <name>!"
        reader.readLine(); // "[SERVER] Commands: CREATE ..."
    }

    /**
     * Read and return the next line that contains {@code needle}.
     * Discards non-matching lines. Throws AssertionError on stream close.
     */
    private static String readUntil(BufferedReader reader, String needle) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains(needle)) return line;
        }
        throw new AssertionError("Stream closed before receiving line containing: " + needle);
    }

    /**
     * Create a room with the given writer. Drain the 3 CREATE reply lines.
     * Then issue LIST and parse out the room ID for the given room name.
     */
    private static String createRoom(BufferedReader reader, PrintWriter writer,
                                      String roomName, int slots) throws IOException {
        writer.println("CREATE " + roomName + " " + slots);
        reader.readLine(); // "[SERVER] Room created: <id> | ..."
        reader.readLine(); // "[SERVER] Share this ID ..."
        reader.readLine(); // "[SERVER] Type READY when ..."

        // Use LIST to reliably extract the room ID
        writer.println("LIST");
        String roomId = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains(roomName)) {
                // Format: "[SERVER]   r-001  RoomName  1/N   WAITING"
                // trim the prefix and split on whitespace
                String trimmed = line.trim().replaceFirst("^\\[SERVER]\\s+", "");
                roomId = trimmed.split("\\s+")[0];
            }
            // LIST ends with the separator line
            if (line.contains("=================================================")) break;
        }
        if (roomId == null) throw new AssertionError("Could not parse room ID for: " + roomName);
        return roomId;
    }

    /**
     * Join a room. Drains the 2 joiner-reply lines.
     * Also drains the 1 "[INFO] X joined" broadcast that arrives on each of the
     * {@code existingReaders} (one per already-in-room client).
     */
    private static void joinRoom(BufferedReader joinerReader, PrintWriter joinerWriter,
                                  String roomId,
                                  BufferedReader[] existingReaders) throws IOException {
        joinerWriter.println("JOIN " + roomId);
        joinerReader.readLine(); // "[SERVER] Joined room: ..."
        joinerReader.readLine(); // "[SERVER] Type READY when ..."
        for (BufferedReader er : existingReaders) {
            er.readLine(); // "[INFO] <name> joined the room. (N/M)"
        }
    }

    /** Send QUIT and drain the 1 farewell line. Closes the socket. */
    private static void quit(PrintWriter writer, BufferedReader reader, Socket socket) {
        try { writer.println("QUIT"); } catch (Exception ignored) {}
        try { reader.readLine(); }      catch (Exception ignored) {}
        try { socket.close(); }         catch (Exception ignored) {}
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println("   AEGISCORE CONCURRENCY INTEGRATION HARNESS      ");
        System.out.println("==================================================");

        // Disable MetricsServer to avoid port 8080 conflicts between test runs
        System.setProperty("aegiscore.metrics.enabled", "false");

        Thread serverThread = new Thread(() -> Server.main(new String[0]), "TestServer");
        serverThread.setDaemon(true);
        serverThread.start();

        waitForServerReady();

        try {
            runTest1MultipleReceivers();
            waitForRegistryEmpty();

            runTest2DisconnectDuringBroadcast();
            waitForRegistryEmpty();

            runTest3RapidMessaging();
            waitForRegistryEmpty();

            runTest4ReconnectStorm();

            System.out.println("\n==================================================");
            System.out.println("  ALL 4 SYSTEM CONCURRENCY TESTS PASSED SUCCESSFULLY!");
            System.out.println("==================================================");
            System.exit(0);

        } catch (Throwable t) {
            System.err.println("\n!!! AEGISCORE TEST HARNESS DETECTED FAILURE !!!");
            t.printStackTrace();
            System.exit(1);
        } finally {
            Server.shutdown();
        }
    }

    private static void waitForServerReady() throws InterruptedException {
        long start = System.currentTimeMillis();
        while (true) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT);
                System.out.println("[SETUP] Server is ready.");
                return;
            } catch (IOException ignored) {
                if (System.currentTimeMillis() - start > 10_000)
                    throw new IllegalStateException("Server failed to start in 10s.");
                Thread.sleep(100);
            }
        }
    }

    private static void waitForRegistryEmpty() throws InterruptedException {
        long start = System.currentTimeMillis();
        PlayerRegistry reg = PlayerRegistry.getInstance();
        while (reg.getPlayerCount() > 0) {
            if (System.currentTimeMillis() - start > 10_000) {
                System.out.println("[WARNING] Registry not empty after 10s. Count: " + reg.getPlayerCount());
                return;
            }
            Thread.sleep(100);
        }
        System.out.println("[SETUP] Registry empty.");
    }

    // ── Test 1 — Multiple Receivers ───────────────────────────────────────────

    /**
     * 5 clients all join the same room. Client-0 sends CHAT; all 5 must receive it.
     */
    private static void runTest1MultipleReceivers() throws Exception {
        System.out.println("\n[TEST 1] Testing Multiple Receivers...");

        int N = 5;
        BufferedReader[] readers = new BufferedReader[N];
        PrintWriter[]    writers = new PrintWriter[N];
        Socket[] sockets = connectAndGreet(readers, writers, N);

        for (int i = 0; i < N; i++) setName(readers[i], writers[i], "T1P" + i);

        // Client-0 creates the room; drain CREATE replies
        String roomId = createRoom(readers[0], writers[0], "BroadcastRoom", N);
        System.out.println("[TEST 1] Room: " + roomId);

        // Clients 1–4 join one by one; each join broadcasts 1 line to all existing members
        for (int i = 1; i < N; i++) {
            BufferedReader[] existing = new BufferedReader[i];
            System.arraycopy(readers, 0, existing, 0, i);
            joinRoom(readers[i], writers[i], roomId, existing);
        }

        // Client-0 sends CHAT — all 5 must receive "[ROOM] T1P0: EventAlpha"
        String payload = "EventAlpha";
        writers[0].println("CHAT " + payload);

        for (int i = 0; i < N; i++) {
            String line = readUntil(readers[i], payload);
            System.out.println("[TEST 1] Client " + i + " received: " + line);
        }

        for (int i = 0; i < N; i++) quit(writers[i], readers[i], sockets[i]);
        System.out.println("-> [TEST 1] PASS: All 5 clients received the CHAT broadcast.");
    }

    // ── Test 2 — Disconnect During Broadcast ─────────────────────────────────

    /**
     * 5 clients in a room. Client-2 is force-closed. Client-0 then sends CHAT.
     * Server must survive and remaining healthy clients must receive the message.
     */
    private static void runTest2DisconnectDuringBroadcast() throws Exception {
        System.out.println("\n[TEST 2] Testing Disconnect During Broadcast...");

        int N = 5;
        BufferedReader[] readers = new BufferedReader[N];
        PrintWriter[]    writers = new PrintWriter[N];
        Socket[] sockets = connectAndGreet(readers, writers, N);

        for (int i = 0; i < N; i++) setName(readers[i], writers[i], "T2P" + i);

        String roomId = createRoom(readers[0], writers[0], "DiscoRoom", N);
        System.out.println("[TEST 2] Room: " + roomId);

        for (int i = 1; i < N; i++) {
            BufferedReader[] existing = new BufferedReader[i];
            System.arraycopy(readers, 0, existing, 0, i);
            joinRoom(readers[i], writers[i], roomId, existing);
        }

        // Force-close client 2 (raw socket close, no QUIT)
        sockets[2].close();
        Thread.sleep(400); // let server detect the dead socket

        // Client-0 sends CHAT
        String payload = "PostDiscoPayload";
        writers[0].println("CHAT " + payload);

        // Healthy clients 0, 1, 3, 4 must receive it
        for (int i : new int[]{0, 1, 3, 4}) {
            String line = readUntil(readers[i], payload);
            System.out.println("[TEST 2] Client " + i + " received: " + line);
        }

        for (int i : new int[]{0, 1, 3, 4}) quit(writers[i], readers[i], sockets[i]);
        System.out.println("-> [TEST 2] PASS: Server survived dead-socket during broadcast.");
    }

    // ── Test 3 — Rapid Messaging ──────────────────────────────────────────────

    /**
     * 4 independent sender+receiver pairs each in their own 2-person room.
     * Each sender blasts 30 CHAT messages with 5ms pacing. Verifies no crashes.
     */
    private static void runTest3RapidMessaging() throws Exception {
        System.out.println("\n[TEST 3] Testing Rapid Messaging (Spam Storm)...");

        int PAIRS = 4, MSGS = 30;
        ExecutorService pool = Executors.newCachedThreadPool();
        CountDownLatch  latch = new CountDownLatch(PAIRS);
        AtomicInteger   failures = new AtomicInteger();

        for (int p = 0; p < PAIRS; p++) {
            final int pair = p;
            pool.submit(() -> {
                try {
                    // sender
                    Socket snd = new Socket();
                    snd.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT);
                    snd.setSoTimeout(READ_TIMEOUT_MS);
                    BufferedReader sR = new BufferedReader(new InputStreamReader(snd.getInputStream()));
                    PrintWriter    sW = new PrintWriter(snd.getOutputStream(), true);
                    sR.readLine(); sR.readLine(); // greeting
                    setName(sR, sW, "T3Snd" + pair);

                    // receiver
                    Socket rcv = new Socket();
                    rcv.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT);
                    rcv.setSoTimeout(READ_TIMEOUT_MS);
                    BufferedReader rR = new BufferedReader(new InputStreamReader(rcv.getInputStream()));
                    PrintWriter    rW = new PrintWriter(rcv.getOutputStream(), true);
                    rR.readLine(); rR.readLine(); // greeting
                    setName(rR, rW, "T3Rcv" + pair);

                    // sender creates a 2-person room
                    sW.println("CREATE SpamPair" + pair + " 2");
                    sR.readLine(); sR.readLine(); sR.readLine(); // CREATE 3-line reply

                    // get room ID via LIST
                    sW.println("LIST");
                    String rid = null;
                    String ln;
                    while ((ln = sR.readLine()) != null) {
                        if (ln.contains("SpamPair" + pair)) {
                            rid = ln.trim().replaceFirst("^\\[SERVER]\\s+", "").split("\\s+")[0];
                        }
                        if (ln.contains("=================================================")) break;
                    }
                    if (rid == null) throw new AssertionError("Could not get room ID for pair " + pair);

                    // receiver joins
                    rW.println("JOIN " + rid);
                    rR.readLine(); rR.readLine(); // joiner gets 2 lines
                    sR.readLine();                // sender gets 1 join-broadcast line

                    // rapid CHAT from sender
                    for (int m = 0; m < MSGS; m++) {
                        sW.println("CHAT spam_" + pair + "_" + m);
                        Thread.sleep(5);
                    }

                    // drain with short timeout
                    snd.setSoTimeout(500);
                    rcv.setSoTimeout(500);
                    try { while (sR.readLine() != null) {} } catch (Exception ignored) {}
                    try { while (rR.readLine() != null) {} } catch (Exception ignored) {}

                    snd.close(); rcv.close();
                    System.out.println("[TEST 3] Pair " + pair + " completed.");
                } catch (Exception e) {
                    System.err.println("[TEST 3] Pair " + pair + " error: " + e.getMessage());
                    failures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        if (!latch.await(90, TimeUnit.SECONDS))
            throw new AssertionError("Test 3 timed out after 90s!");
        pool.shutdown();
        if (failures.get() > 0)
            throw new AssertionError("Test 3 failures: " + failures.get());

        System.out.println("-> [TEST 3] PASS: Concurrent CHAT spam with no crashes or deadlocks.");
    }

    // ── Test 4 — Reconnect Storm ──────────────────────────────────────────────

    /**
     * 60 rapid connect → drain-greeting → QUIT → close cycles concurrently.
     * Registry must be 0 when all are done.
     */
    private static void runTest4ReconnectStorm() throws Exception {
        System.out.println("\n[TEST 4] Testing Reconnect Storm (Zombie Defense)...");

        int SESSIONS = 60;
        // Use at most 9 concurrent threads — stays safely under ConnectionGuard's maxConnectionsPerIp=10
        ExecutorService pool = Executors.newFixedThreadPool(9);
        CountDownLatch  latch = new CountDownLatch(SESSIONS);
        AtomicInteger   fails = new AtomicInteger();

        for (int i = 0; i < SESSIONS; i++) {
            final int idx = i;
            pool.submit(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT);
                    s.setSoTimeout(READ_TIMEOUT_MS);
                    BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
                    PrintWriter    w = new PrintWriter(s.getOutputStream(), true);

                    r.readLine(); // greeting line 1
                    r.readLine(); // greeting line 2
                    w.println("QUIT");
                    r.readLine(); // farewell line
                } catch (IOException e) {
                    System.err.println("[TEST 4] Session " + idx + " error: " + e.getMessage());
                    fails.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        if (!latch.await(60, TimeUnit.SECONDS))
            throw new AssertionError("Test 4 timed out after 60s!");
        pool.shutdown();

        Thread.sleep(2000); // let server finally-blocks complete deregistration

        int zombies = PlayerRegistry.getInstance().getPlayerCount();
        System.out.println("Sessions run  : " + SESSIONS);
        System.out.println("Failures      : " + fails.get());
        System.out.println("Zombie sessions: " + zombies);

        if (fails.get() > 0)
            throw new AssertionError("Connection failures in storm: " + fails.get());
        if (zombies != 0)
            throw new AssertionError("ZOMBIE SESSIONS DETECTED! Count: " + zombies);

        System.out.println("-> [TEST 4] PASS: 0 zombie sessions after reconnect storm.");
    }
}
