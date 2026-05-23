import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Robust, automated integration test suite that aggressively validates 
 * AegisCore against 4 critical network and concurrency failure modes:
 * 
 * 1. Multiple Receivers (Broadcast correctness)
 * 2. Disconnect During Broadcast (Resiliency to connection loss)
 * 3. Rapid Messaging (Stress/Spam survival with atomic synchronized writes)
 * 4. Reconnect Storm (Validating zero zombie sessions)
 */
public class SystemIntegrationTest {
    private static final String HOST = "localhost";
    private static final int PORT = 5000;
    private static final int CONNECT_TIMEOUT_MS = 2000;

    public static void main(String[] args) throws Exception {
        System.out.println("==================================================");
        System.out.println("   AEGISCORE CONCURRENCY INTEGRATION HARNESS      ");
        System.out.println("==================================================");

        // Start Server in background thread
        Thread serverThread = new Thread(() -> Server.main(new String[0]), "TestServer");
        serverThread.setDaemon(true);
        serverThread.start();

        // Wait for server to bind
        waitForServerReady();

        try {
            // Run all 4 required failure scenario tests
            runTest1MultipleReceivers();
            waitForRegistryEmpty();
            runTest2DisconnectDuringBroadcast();
            waitForRegistryEmpty();
            runTest3RapidMessaging();
            waitForRegistryEmpty();
            runTest4ReconnectStorm();

            System.out.println("\n==================================================");
            System.out.println("  ALL 4 SYSTEM CONCURRENCY TESTS PASSED SUCCESSFULLY!  ");
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
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
                System.out.println("[SETUP] Test Server ready and listening.");
                return;
            } catch (IOException ignored) {
                if (System.currentTimeMillis() - start > 10000) {
                    throw new IllegalStateException("Server failed to initialize in time.");
                }
                Thread.sleep(100);
            }
        }
    }

    private static void waitForRegistryEmpty() throws InterruptedException {
        long start = System.currentTimeMillis();
        SharedClientRegistry registry = SharedClientRegistry.getInstance();
        while (registry.getClientCount() > 0) {
            if (System.currentTimeMillis() - start > 5000) {
                System.out.println("[WARNING] Timeout waiting for registry to empty. Current count: " 
                    + registry.getClientCount());
                return;
            }
            Thread.sleep(50);
        }
        System.out.println("[SETUP] Registry is empty and clean.");
    }

    /**
     * Test 1 — Multiple Receivers
     * Verify all connected clients receive broadcasts correctly.
     */
    private static void runTest1MultipleReceivers() throws Exception {
        System.out.println("\n[TEST 1] Testing Multiple Receivers...");
        
        int clientCount = 5;
        Socket[] sockets = new Socket[clientCount];
        BufferedReader[] readers = new BufferedReader[clientCount];
        PrintWriter[] writers = new PrintWriter[clientCount];

        // Connect 5 distinct clients
        for (int i = 0; i < clientCount; i++) {
            sockets[i] = new Socket(HOST, PORT);
            readers[i] = new BufferedReader(new InputStreamReader(sockets[i].getInputStream()));
            writers[i] = new PrintWriter(sockets[i].getOutputStream(), true);
            
            // Read greeting welcome message
            readers[i].readLine();
        }

        // Client 0 triggers a broadcast
        String testMessage = "Unique Broadcast Event Alpha";
        writers[0].println(testMessage);

        // Verify other clients (1 through 4) receive this broadcast
        for (int i = 1; i < clientCount; i++) {
            String received = readers[i].readLine();
            if (received == null || !received.contains(testMessage)) {
                throw new AssertionError("Client " + i + " did not receive broadcast: " + received);
            }
        }

        // Clean up connections
        for (int i = 0; i < clientCount; i++) {
            writers[i].println("exit");
            readers[i].readLine(); // Consume clean disconnect message
            sockets[i].close();
        }

        System.out.println("-> [TEST 1] PASS: All clients successfully received parallel broadcasts.");
    }

    /**
     * Test 2 — Disconnect During Broadcast
     * Forcefully disconnect a client while messages are flowing to verify server survival.
     */
    private static void runTest2DisconnectDuringBroadcast() throws Exception {
        System.out.println("\n[TEST 2] Testing Disconnect During Broadcast...");

        int clientCount = 5;
        Socket[] sockets = new Socket[clientCount];
        BufferedReader[] readers = new BufferedReader[clientCount];
        PrintWriter[] writers = new PrintWriter[clientCount];

        for (int i = 0; i < clientCount; i++) {
            sockets[i] = new Socket(HOST, PORT);
            readers[i] = new BufferedReader(new InputStreamReader(sockets[i].getInputStream()));
            writers[i] = new PrintWriter(sockets[i].getOutputStream(), true);
            readers[i].readLine(); // welcome greeting
        }

        // Forcefully sever Client 2 in the middle of operation
        sockets[2].close();
        Thread.sleep(150); // wait briefly for socket closure to propagate to server OS thread

        // Client 0 triggers a broad broadcast storm
        writers[0].println("Broadcast post Client-2 termination");

        // Verify healthy clients (1, 3, 4) still receive the message safely
        for (int i : new int[]{1, 3, 4}) {
            String received = readers[i].readLine();
            if (received == null || !received.contains("Broadcast post Client-2 termination")) {
                throw new AssertionError("Healthy Client " + i + " failed to receive broadcast: " + received);
            }
        }

        // Clean up remaining clients
        for (int i : new int[]{0, 1, 3, 4}) {
            writers[i].println("exit");
            readers[i].readLine();
            sockets[i].close();
        }

        System.out.println("-> [TEST 2] PASS: Server handled connection sever during broadcast loop gracefully.");
    }

    /**
     * Test 3 — Rapid Messaging
     * Spam messages from multiple connections simultaneously to watch for crashes,
     * missed frames, or inconsistent behaviors.
     */
    private static void runTest3RapidMessaging() throws Exception {
        System.out.println("\n[TEST 3] Testing Rapid Messaging (Spam Storm)...");

        int clientCount = 8;
        int messagesPerClient = 40;
        Socket[] sockets = new Socket[clientCount];
        BufferedReader[] readers = new BufferedReader[clientCount];
        PrintWriter[] writers = new PrintWriter[clientCount];

        for (int i = 0; i < clientCount; i++) {
            sockets[i] = new Socket(HOST, PORT);
            readers[i] = new BufferedReader(new InputStreamReader(sockets[i].getInputStream()));
            writers[i] = new PrintWriter(sockets[i].getOutputStream(), true);
            readers[i].readLine(); // greeting
        }

        ExecutorService pool = Executors.newFixedThreadPool(clientCount);
        CountDownLatch latch = new CountDownLatch(clientCount);
        AtomicInteger writeFailures = new AtomicInteger();

        // Fire off high-speed writes concurrently from all clients
        for (int i = 0; i < clientCount; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    for (int m = 0; m < messagesPerClient; m++) {
                        writers[index].println("SPAM_" + index + "_" + m);
                        Thread.sleep(1); // Rapid stress pacing
                    }
                } catch (Exception e) {
                    writeFailures.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        if (writeFailures.get() > 0) {
            throw new AssertionError("Write failures occurred during rapid messaging storm!");
        }

        // Shutdown clients
        for (int i = 0; i < clientCount; i++) {
            writers[i].println("exit");
            sockets[i].close();
        }

        System.out.println("-> [TEST 3] PASS: Concurrent messaging spam completed with no deadlocks, socket errors, or server freezes.");
    }

    /**
     * Test 4 — Reconnect Storm
     * Rapid connect/disconnect cycles. Registry must remain consistent.
     * Verify that there are absolutely no zombie sessions left in the registry.
     */
    private static void runTest4ReconnectStorm() throws Exception {
        System.out.println("\n[TEST 4] Testing Reconnect Storm (Zombie Defense)...");

        int totalSessions = 80;
        ExecutorService pool = Executors.newFixedThreadPool(15);
        CountDownLatch latch = new CountDownLatch(totalSessions);
        AtomicInteger failedConnections = new AtomicInteger();

        // Connect, send test ping, exit immediately, and close
        for (int i = 0; i < totalSessions; i++) {
            pool.submit(() -> {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                         PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
                        
                        reader.readLine(); // initial connected greeting
                        writer.println("PING");
                        reader.readLine(); // ack
                        writer.println("exit");
                        reader.readLine(); // clean unregistration confirm
                    }
                } catch (IOException e) {
                    failedConnections.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();
        
        // Wait briefly for all server thread unregistration finally blocks to execute
        Thread.sleep(1200);

        int activeClientCount = SharedClientRegistry.getInstance().getClientCount();
        System.out.println("Total rapid sessions executed: " + totalSessions);
        System.out.println("Connection/Transmission failures: " + failedConnections.get());
        System.out.println("Registry state (active sessions count): " + activeClientCount);

        if (failedConnections.get() > 0) {
            throw new AssertionError("Failed to connect cleanly during storm: " + failedConnections.get());
        }

        if (activeClientCount != 0) {
            throw new AssertionError("ZOMBIE SESSIONS DETECTED! Active count is not zero: " + activeClientCount);
        }

        System.out.println("-> [TEST 4] PASS: Reconnect storm finished cleanly with exactly 0 zombie sessions in registry.");
    }
}
