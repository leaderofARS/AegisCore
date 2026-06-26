package cluster;

import core.Logger;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Represents a connection to a peer node in the AegisCore cluster.
 *
 * <p>Handles bidirectional message delivery and maintains a reading loop on a virtual thread.
 */
public class ClusterNode implements Runnable {

    private final Socket socket;
    private final ClusterManager manager;
    private final String remoteNodeId; // Initially unknown if accepted, populated on join handshake
    private volatile boolean active = true;
    private PrintWriter writer;

    public ClusterNode(Socket socket, ClusterManager manager, String remoteNodeId) {
        this.socket = socket;
        this.manager = manager;
        this.remoteNodeId = remoteNodeId;
    }

    public boolean isActive() {
        return active;
    }

    public String getRemoteNodeId() {
        return remoteNodeId;
    }

    public Socket getSocket() {
        return socket;
    }

    @Override
    public void run() {
        String ip = socket.getInetAddress().getHostAddress();
        int port = socket.getPort();
        Logger.logServer("Cluster connection established with peer: " + ip + ":" + port);

        try (
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
            )
        ) {
            this.writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), 
                true
            );

            // Send local node identification as handshake join
            send(new ClusterMessage("NODE_JOIN", ClusterConfig.getInstance().getNodeId(), new String[0]));

            String line;
            while (active && (line = reader.readLine()) != null) {
                ClusterMessage msg = ClusterMessage.parse(line);
                if (msg != null) {
                    manager.handleIncomingMessage(this, msg);
                }
            }

        } catch (IOException e) {
            if (active) {
                Logger.logServerError("Cluster peer connection error with " + ip + ": " + e.getMessage());
            }
        } finally {
            close();
        }
    }

    public synchronized void send(ClusterMessage msg) {
        if (!active || writer == null) {
            return;
        }
        writer.println(msg.serialize());
    }

    public void close() {
        if (!active) return;
        active = false;
        try {
            socket.close();
        } catch (IOException e) {
            // Ignored
        }
        manager.removeNode(this);
        Logger.logServer("Cluster connection closed for remote node: " + remoteNodeId);
    }
}
