import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import server.Server;
import player.PlayerRegistry;

public class LoadTest {
    private static final String HOST = "localhost";
    private static final int PORT = 5000;
    private static final int DEFAULT_CLIENT_COUNT = 50;
    private static final int DEFAULT_CYCLE_COUNT = 20;
    private static final int MAX_THREAD_POOL = 50;
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int WAIT_SERVER_MS = 100;
    private static final int WAIT_SERVER_TIMEOUT_MS = 15000;
    private static final int SLEEP_MIN_MS = 5;
    private static final int SLEEP_MAX_MS = 35;

    public static void main(String[] args) throws Exception {
        int clientCount = args.length > 0 ? parsePositiveInt(args[0], DEFAULT_CLIENT_COUNT) : DEFAULT_CLIENT_COUNT;
        int cycleCount = args.length > 1 ? parsePositiveInt(args[1], DEFAULT_CYCLE_COUNT) : DEFAULT_CYCLE_COUNT;

        System.out.println("[LOADTEST] Starting load test: " + clientCount + " clients, " + cycleCount + " cycles each.");

        Thread serverThread = new Thread(() -> Server.main(new String[0]), "LoadTest-Server");
        serverThread.setDaemon(true);
        serverThread.start();

        waitForServerReady();

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(clientCount, MAX_THREAD_POOL));
        CountDownLatch latch = new CountDownLatch(clientCount);
        AtomicInteger successfulSessions = new AtomicInteger();
        AtomicInteger failedSessions = new AtomicInteger();
        List<String> failures = Collections.synchronizedList(new ArrayList<String>());

        for (int clientId = 1; clientId <= clientCount; clientId++) {
            final int id = clientId;
            pool.submit(() -> {
                try {
                    runClientCycles(id, cycleCount, successfulSessions, failedSessions, failures);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);

        int finalRegistryCount = PlayerRegistry.getInstance().getPlayerCount();

        System.out.println("[LOADTEST] Completed.");
        System.out.println("[LOADTEST] Successful sessions: " + successfulSessions.get());
        System.out.println("[LOADTEST] Failed sessions: " + failedSessions.get());
        System.out.println("[LOADTEST] Final registry client count: " + finalRegistryCount);

        if (!failures.isEmpty()) {
            System.out.println("[LOADTEST] Sample failure: " + failures.get(0));
        }

        Server.shutdown();
        serverThread.join(2000);

        if (failedSessions.get() > 0 || finalRegistryCount != 0) {
            System.err.println("[LOADTEST] FAILURE detected: lifecycle issues or registry cleanup failure.");
            System.exit(1);
        }

        System.out.println("[LOADTEST] PASS: concurrent connect/disconnect stress test completed cleanly.");
        System.exit(0);
    }

    private static void runClientCycles(int clientId,
                                        int cycleCount,
                                        AtomicInteger successfulSessions,
                                        AtomicInteger failedSessions,
                                        List<String> failures) {
        Random random = new Random();

        for (int cycle = 1; cycle <= cycleCount; cycle++) {
            try {
                runClientSession(clientId, cycle);
                successfulSessions.incrementAndGet();
            } catch (IOException e) {
                failedSessions.incrementAndGet();
                failures.add("Client " + clientId + " cycle " + cycle + " failed: " + e.getMessage());
            }

            sleep(random.nextInt(SLEEP_MAX_MS - SLEEP_MIN_MS + 1) + SLEEP_MIN_MS);
        }
    }

    private static void runClientSession(int clientId, int cycle) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {

                String greeting = reader.readLine();
                if (greeting == null) {
                    throw new IOException("Server did not send initial greeting");
                }

                writer.println("HELLO " + clientId + " " + cycle);
                String response = reader.readLine();
                if (response == null) {
                    throw new IOException("Server did not acknowledge HELLO message");
                }

                if (cycle % 2 == 0) {
                    writer.println("PING");
                    String pingResponse = reader.readLine();
                    if (pingResponse == null) {
                        throw new IOException("Server did not respond to PING");
                    }
                }

                writer.println("exit");
                reader.readLine();
            }
        }
    }

    private static void waitForServerReady() throws InterruptedException {
        long start = System.currentTimeMillis();
        while (true) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);
                System.out.println("[LOADTEST] Server is accepting connections.");
                return;
            } catch (IOException ignored) {
                if (System.currentTimeMillis() - start > WAIT_SERVER_TIMEOUT_MS) {
                    throw new IllegalStateException("Server did not start in time.");
                }
                Thread.sleep(WAIT_SERVER_MS);
            }
        }
    }

    private static int parsePositiveInt(String value, int defaultValue) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
