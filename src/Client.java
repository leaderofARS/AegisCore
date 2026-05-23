import java.io.*;
import java.net.*;

// -------------------------------------------------------------------------
// CLIENT
// -------------------------------------------------------------------------

/**
 * A command-line TCP client for the AegisCore multithreaded server system.
 *
 * <p>This class serves as the primary test client for AegisCore. It establishes a single
 * TCP connection to the AegisCore server and implements a two-thread I/O model:
 *
 * <ul>
 *   <li><b>Main thread</b> – reads lines from standard input (the user's terminal) and
 *       forwards each line to the server over the open socket.</li>
 *   <li><b>ServerListener daemon thread</b> – continuously reads broadcast messages sent
 *       by the server and logs them to the console in real-time, without blocking the
 *       main thread.</li>
 * </ul>
 *
 * <p><b>Threading model:</b> This class is <em>not</em> thread-safe as a unit; it is
 * designed to be run once via {@link #main(String[])} from the JVM entry point. The
 * {@code ServerListener} thread is configured as a daemon, so the JVM exits automatically
 * once the main thread terminates (either because the user typed {@code exit} or because
 * standard input was closed). No external synchronisation is required between the two
 * threads because each thread owns exclusive access to a distinct I/O stream.
 *
 * <p><b>Design pattern:</b> The listener thread follows the <em>Reactor</em> pattern —
 * it blocks on incoming data and reacts by logging each message as it arrives, keeping
 * the main send-loop fully independent.
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 *   // Start the AegisCore server, then run:
 *   java Client
 * }</pre>
 */
public class Client {

    // -------------------------------------------------------------------------
    // CONSTANTS
    // -------------------------------------------------------------------------

    /** Hostname of the AegisCore server to connect to. */
    private static final String HOST = "localhost";

    /**
     * TCP port number on which the AegisCore server is listening.
     *
     * <p>Must match the port configured in {@code Server}.
     */
    private static final int PORT = 5000;

    // -------------------------------------------------------------------------
    // ENTRY POINT
    // -------------------------------------------------------------------------

    /**
     * Connects to the AegisCore server and starts the two-thread I/O loop.
     *
     * <p>The method performs the following steps in order:
     * <ol>
     *   <li>Opens a blocking TCP socket to {@value #HOST}:{@value #PORT}.</li>
     *   <li>Wraps the socket streams in buffered readers/writers for line-oriented I/O.</li>
     *   <li>Spawns a daemon {@code ServerListener} thread that reads and logs all
     *       server-originated messages until the connection closes.</li>
     *   <li>Enters a blocking loop on standard input, forwarding every line to the
     *       server. Typing {@code exit} (case-insensitive) or closing stdin ends the
     *       loop cleanly.</li>
     *   <li>Closes the socket and stdin reader, which also causes the daemon listener
     *       thread to unblock and terminate.</li>
     * </ol>
     *
     * @param args command-line arguments (not used).
     */
    public static void main(String[] args) {
        try {
            Socket socket = new Socket(HOST, PORT);
            Logger.logClient("Connected to server at " + HOST + ":" + PORT);

            // Wrap the socket's input stream in a BufferedReader for efficient line-by-line
            // reading of server messages without incurring a system call per character.
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // Auto-flush is enabled so every println() immediately flushes to the network
            // without requiring an explicit flush() call after each user message.
            PrintWriter output = new PrintWriter(socket.getOutputStream(), true);

            // Wrap System.in so the main thread can read complete lines typed by the user.
            BufferedReader userInput = new BufferedReader(new InputStreamReader(System.in));

            // -------------------------------------------------------------------------
            // SERVER LISTENER THREAD
            // -------------------------------------------------------------------------

            // Spawn a dedicated thread to receive and log server broadcasts in real-time.
            // Running this on a separate thread prevents incoming messages from being
            // delayed or dropped while the main thread is blocked waiting for user input.
            Thread listenerThread = new Thread(() -> {
                try {
                    String serverMessage;
                    // Blocks until the server sends a line, then immediately logs it.
                    // A null return value signals that the server has closed the connection.
                    while ((serverMessage = input.readLine()) != null) {
                        Logger.logClient(serverMessage);
                    }
                } catch (IOException e) {
                    // The main thread closes the socket upon a clean exit, which causes
                    // readLine() to throw an IOException. The isClosed() guard suppresses
                    // that expected exception so only genuine, unexpected I/O errors are
                    // reported to the log.
                    if (!socket.isClosed()) {
                        Logger.logClientError("Error reading from server: " + e.getMessage());
                    }
                }
            });

            // Mark the listener as a daemon so the JVM does not wait for it to finish
            // when the main thread exits — the OS will reclaim resources automatically.
            listenerThread.setDaemon(true);

            // Assign a meaningful name to aid in debugging thread dumps.
            listenerThread.setName("ServerListener");
            listenerThread.start();

            // -------------------------------------------------------------------------
            // MAIN SEND LOOP
            // -------------------------------------------------------------------------

            // Read user input line-by-line and forward it to the server.
            // The loop terminates on EOF (null from readLine) or an explicit "exit" command.
            while (true) {
                String message = userInput.readLine();

                // A null line signals EOF — the user closed stdin (e.g. Ctrl+D / Ctrl+Z).
                if (message == null) {
                    break;
                }

                // Forward the typed line to the server; auto-flush ensures immediate delivery.
                output.println(message);

                // Honour an explicit "exit" command by breaking out of the send loop,
                // which allows the finally-style cleanup below to close the connection neatly.
                if (message.equalsIgnoreCase("exit")) {
                    Logger.logClient("Disconnecting cleanly...");
                    break;
                }
            }

            // -------------------------------------------------------------------------
            // CLEANUP
            // -------------------------------------------------------------------------

            // Closing the socket unblocks the ServerListener thread's readLine() call,
            // causing it to receive either null or an IOException, after which it exits.
            // Because the listener is a daemon, the JVM will not wait for it regardless.
            socket.close();
            userInput.close();
            Logger.logClient("Client stopped.");

        } catch (IOException e) {
            // Catches both connection-establishment failures and unexpected mid-session
            // errors that escape the inner catch blocks.
            Logger.logClientError("Client failed: " + e.getMessage());
        }
    }
}