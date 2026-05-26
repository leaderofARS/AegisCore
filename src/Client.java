import java.io.*;
import java.net.*;

/**
 * Command-line TCP client for the AegisCore server.
 *
 * <p>Establishes a single connection to the server and runs a two-thread I/O model:
 * <ul>
 *   <li><b>Main thread</b> — reads lines from {@link System#in} and forwards them to the server.</li>
 *   <li><b>ServerListener thread</b> (daemon) — continuously reads and logs server messages
 *       without blocking the send loop.</li>
 * </ul>
 *
 * <p>The listener is a daemon thread, so the JVM exits automatically once the main thread
 * finishes — no explicit coordination between the two threads is needed.
 */
public class Client {

    private static final String HOST = "localhost";
    private static final int    PORT = 5000;

    /**
     * Connects to the server at {@value #HOST}:{@value #PORT}, starts the listener thread,
     * and enters the send loop. Closes the connection on {@code exit} or EOF.
     *
     * @param args command-line arguments; not used
     */
    public static void main(String[] args) {
        try {
            Socket socket = new Socket(HOST, PORT);
            Logger.logClient("Connected to server at " + HOST + ":" + PORT);

            BufferedReader input     = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter    output    = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader userInput = new BufferedReader(new InputStreamReader(System.in));

            Thread listenerThread = new Thread(() -> {
                try {
                    String serverMessage;
                    while ((serverMessage = input.readLine()) != null) {
                        Logger.logClient(serverMessage);
                    }
                } catch (IOException e) {
                    if (!socket.isClosed()) {
                        Logger.logClientError("Error reading from server: " + e.getMessage());
                    }
                } catch (Throwable t) {
                    Logger.logClientError("Unexpected crash in listener thread: " + t.getMessage());
                }
            });

            listenerThread.setDaemon(true);
            listenerThread.setName("ServerListener");
            listenerThread.start();

            while (true) {
                String message = userInput.readLine();
                if (message == null) { break; }
                output.println(message);
                if (message.equalsIgnoreCase("exit")) {
                    Logger.logClient("Disconnecting...");
                    break;
                }
            }

            socket.close();
            userInput.close();
            Logger.logClient("Client stopped.");

        } catch (IOException e) {
            Logger.logClientError("Client failed to connect to " + HOST + ":" + PORT + " — " + e.getMessage());
        }
    }
}