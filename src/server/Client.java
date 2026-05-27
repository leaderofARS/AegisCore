package server;

import core.Logger;
import java.io.*;
import java.net.*;

/**
 * Interactive command-line client for the AegisCore Game Lobby Server.
 *
 * <p>Establishes a TCP connection and runs a two-thread I/O model:
 * <ul>
 *   <li><b>Main thread</b> — reads lines from {@link System#in} and sends them to the server.</li>
 *   <li><b>ServerListener thread</b> (daemon) — continuously reads and prints server responses.</li>
 * </ul>
 *
 * <p>Type {@code NAME <username>} immediately after connecting, then use any lobby command.
 * Type {@code QUIT} or press Ctrl-C to disconnect.
 */
public class Client {

    private static final String HOST = "localhost";
    private static final int    PORT = 5000;

    /**
     * Connects to the AegisCore server and starts the send/receive loop.
     *
     * @param args command-line arguments; not used
     */
    public static void main(String[] args) {
        try {
            Socket socket = new Socket(HOST, PORT);
            Logger.logClient("Connected to AegisCore at " + HOST + ":" + PORT);

            BufferedReader serverIn  = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            PrintWriter    serverOut = new PrintWriter(socket.getOutputStream(), true);
            BufferedReader userIn    = new BufferedReader(new InputStreamReader(System.in));

            Thread listener = new Thread(() -> {
                try {
                    String msg;
                    while ((msg = serverIn.readLine()) != null) {
                        System.out.println(msg);
                    }
                } catch (IOException e) {
                    if (!socket.isClosed()) {
                        Logger.logClientError("Server read error: " + e.getMessage());
                    }
                } catch (Throwable t) {
                    Logger.logClientError("Listener crash: " + t.getMessage());
                }
            });
            listener.setDaemon(true);
            listener.setName("ServerListener");
            listener.start();

            while (true) {
                String line = userIn.readLine();
                if (line == null) { break; }
                serverOut.println(line);
                if (line.equalsIgnoreCase("quit")) { break; }
            }

            socket.close();
            userIn.close();
            Logger.logClient("Disconnected.");

        } catch (IOException e) {
            Logger.logClientError("Failed to connect to " + HOST + ":" + PORT + " — " + e.getMessage());
        }
    }
}
