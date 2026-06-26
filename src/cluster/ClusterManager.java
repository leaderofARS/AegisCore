package cluster;

import core.Logger;
import player.Player;
import player.PlayerRegistry;
import player.PlayerStatus;
import room.Room;
import room.RoomRegistry;
import room.RoomState;
import room.RoomConfig;
import protocol.CommandRouter;
import protocol.CommandContext;
import protocol.CommandType;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/**
 * Singleton coordinator of the AegisCore Clustering Subsystem.
 *
 * <p>Manages peer connections, replicates room and player directories,
 * and routes commands/messages for distributed lobby scaling.
 */
public final class ClusterManager {

    private static final ClusterManager instance = new ClusterManager();

    private final ClusterConfig config = ClusterConfig.getInstance();
    private final CopyOnWriteArrayList<ClusterNode> activeNodes = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, RemoteRoomInfo> remoteRooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> remotePlayers = new ConcurrentHashMap<>(); // name -> nodeId
    private final ConcurrentHashMap<String, Player> playerProxies = new ConcurrentHashMap<>(); // sessionId -> Proxy Player

    private ServerSocket serverSocket;
    private volatile boolean running = false;
    private CommandRouter commandRouter;

    private ClusterManager() {}

    public static ClusterManager getInstance() {
        return instance;
    }

    public void initialize(CommandRouter router) {
        this.commandRouter = router;
    }

    public void start() {
        if (!config.isEnabled()) {
            Logger.logServer("Clustering is disabled.");
            return;
        }

        running = true;
        Logger.logServer("Starting AegisCore Cluster Node: " + config.getNodeId() + " on port " + config.getPort());

        // 1. Boot Server Socket to accept peer connections
        Thread.ofVirtual().name("ClusterServer-" + config.getPort()).start(() -> {
            try {
                serverSocket = new ServerSocket(config.getPort());
                while (running) {
                    Socket peerSocket = serverSocket.accept();
                    ClusterNode node = new ClusterNode(peerSocket, this, null);
                    Thread.ofVirtual().name("ClusterNode-Inbound-" + peerSocket.getPort()).start(node);
                }
            } catch (IOException e) {
                if (running) {
                    Logger.logServerError("Cluster ServerSocket failure: " + e.getMessage());
                }
            }
        });

        // 2. Connect to listed peer configurations
        connectToPeers();

        // 3. Periodic state reconciliation / health check task
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ClusterMonitor");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(this::reconcileClusterState, 5, 5, TimeUnit.SECONDS);
    }

    private void connectToPeers() {
        String peerList = config.getPeers();
        if (peerList.isBlank()) return;

        String[] peers = peerList.split(",");
        for (String peer : peers) {
            String[] hostPort = peer.trim().split(":");
            if (hostPort.length != 2) continue;

            String host = hostPort[0];
            int port = Integer.parseInt(hostPort[1]);

            Thread.ofVirtual().name("ClusterConnect-" + peer).start(() -> {
                int retries = 5;
                while (running && retries > 0) {
                    try {
                        Socket socket = new Socket(host, port);
                        ClusterNode node = new ClusterNode(socket, this, null);
                        Thread.ofVirtual().name("ClusterNode-Outbound-" + port).start(node);
                        break;
                    } catch (IOException e) {
                        retries--;
                        Logger.logServer("Could not connect to peer " + peer + ". Retrying... (" + retries + " left)");
                        try { Thread.sleep(3000); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    }
                }
            });
        }
    }

    public void stop() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException e) { /* Ignored */ }
        }
        for (ClusterNode node : activeNodes) {
            node.close();
        }
        activeNodes.clear();
        Logger.logServer("Cluster node stopped.");
    }

    public void registerNode(ClusterNode node) {
        activeNodes.add(node);
        // Sync all local rooms to the newly joined node
        for (Room room : RoomRegistry.getInstance().getOpenRooms()) {
            node.send(new ClusterMessage("ROOM_SYNC", config.getNodeId(), new String[]{
                room.getRoomId(), room.getName(), room.getOwnerSessionId(),
                String.valueOf(room.getMaxPlayers()), room.getState().name()
            }));
        }
    }

    public void removeNode(ClusterNode node) {
        activeNodes.remove(node);
        // Remove all rooms and players hosted on this disconnected node
        String remoteNodeId = node.getRemoteNodeId();
        if (remoteNodeId != null) {
            remoteRooms.entrySet().removeIf(e -> e.getValue().nodeId().equals(remoteNodeId));
            remotePlayers.values().removeIf(nodeId -> nodeId.equals(remoteNodeId));
        }
    }

    public void broadcast(ClusterMessage msg) {
        for (ClusterNode node : activeNodes) {
            node.send(msg);
        }
    }

    public void handleIncomingMessage(ClusterNode peer, ClusterMessage msg) {
        switch (msg.type()) {
            case "NODE_JOIN" -> {
                // Associate the remote node ID with this connection
                ClusterNode nodeWithId = new ClusterNode(peer.getSocket(), this, msg.senderNodeId());
                activeNodes.remove(peer);
                registerNode(nodeWithId);
                Thread.ofVirtual().name("ClusterNode-" + msg.senderNodeId()).start(nodeWithId);
                Logger.logServer("Cluster node joined: " + msg.senderNodeId());
            }
            case "PLAYER_SYNC" -> {
                String name = msg.args()[0];
                remotePlayers.put(name.toLowerCase(), msg.senderNodeId());
            }
            case "PLAYER_LEAVE" -> {
                String name = msg.args()[0];
                remotePlayers.remove(name.toLowerCase());
            }
            case "ROOM_SYNC" -> {
                String roomId = msg.args()[0];
                String name = msg.args()[1];
                String owner = msg.args()[2];
                int max = Integer.parseInt(msg.args()[3]);
                RoomState state = RoomState.valueOf(msg.args()[4]);
                remoteRooms.put(roomId, new RemoteRoomInfo(roomId, name, owner, max, state, msg.senderNodeId()));
            }
            case "ROOM_DESTROY" -> {
                String roomId = msg.args()[0];
                remoteRooms.remove(roomId);
            }
            case "WHISPER_ROUTE" -> {
                String targetName = msg.args()[0];
                String senderName = msg.args()[1];
                String message = msg.args()[2];
                Player target = PlayerRegistry.getInstance().getPlayerByName(targetName);
                if (target != null) {
                    target.send("[WHISPER] " + senderName + " -> you: " + message);
                }
            }
            case "REQUEST_JOIN_ROOM" -> {
                String roomId = msg.args()[0];
                String playerSessionId = msg.args()[1];
                String playerName = msg.args()[2];

                Room room = RoomRegistry.getInstance().getRoom(roomId);
                if (room != null && !room.isFull() && room.getState() == RoomState.WAITING) {
                    // Create a proxy player representing the remote player on Node 1
                    server.ClientHandler proxyHandler = new server.ClientHandler(peer.getSocket(), PlayerRegistry.getInstance(), commandRouter) {
                        @Override
                        public void sendMessage(String text) {
                            peer.send(new ClusterMessage("PLAYER_MSG_FORWARD", config.getNodeId(), new String[]{
                                playerSessionId, text
                            }));
                        }
                    };
                    Player proxy = new Player(playerSessionId, proxyHandler);
                    proxy.setDisplayName(playerName);
                    proxy.setStatus(PlayerStatus.IN_ROOM);
                    proxy.setCurrentRoomId(roomId);
                    playerProxies.put(playerSessionId, proxy);

                    room.addPlayer(proxy);
                    room.broadcast("[INFO] " + proxy.getLabel() + " joined the room. (" + room.getPlayerCount() + "/" + room.getMaxPlayers() + ")");
                    
                    peer.send(new ClusterMessage("JOIN_ROOM_SUCCESS", config.getNodeId(), new String[]{
                        roomId, playerSessionId
                    }));
                } else {
                    peer.send(new ClusterMessage("JOIN_ROOM_FAIL", config.getNodeId(), new String[]{
                        roomId, playerSessionId, "Room is full or closed"
                    }));
                }
            }
            case "JOIN_ROOM_SUCCESS" -> {
                String roomId = msg.args()[0];
                String playerSessionId = msg.args()[1];
                Player player = PlayerRegistry.getInstance().getPlayer(playerSessionId);
                if (player != null) {
                    player.setCurrentRoomId(roomId);
                    player.setStatus(PlayerStatus.IN_ROOM);
                    player.send("[SERVER] Joined cluster room: " + roomId);
                    player.send("[SERVER] Type READY when you are prepared to start.");
                }
            }
            case "JOIN_ROOM_FAIL" -> {
                String playerSessionId = msg.args()[1];
                String reason = msg.args()[2];
                Player player = PlayerRegistry.getInstance().getPlayer(playerSessionId);
                if (player != null) {
                    player.send("[ERROR] Joining cluster room failed: " + reason);
                }
            }
            case "PLAYER_MSG_FORWARD" -> {
                String playerSessionId = msg.args()[0];
                String text = msg.args()[1];
                Player player = PlayerRegistry.getInstance().getPlayer(playerSessionId);
                if (player != null) {
                    player.send(text);
                }
            }
            case "FORWARD_PLAYER_CMD" -> {
                String roomId = msg.args()[0];
                String playerSessionId = msg.args()[1];
                String cmdLine = msg.args()[2];

                Player proxy = playerProxies.get(playerSessionId);
                if (proxy != null && commandRouter != null) {
                    // Process commands in context of the proxy player
                    commandRouter.route(proxy, cmdLine);
                }
            }
        }
    }

    public boolean isRemoteRoom(String roomId) {
        return remoteRooms.containsKey(roomId);
    }

    public void requestJoinRemoteRoom(Player player, String roomId) {
        RemoteRoomInfo info = remoteRooms.get(roomId);
        if (info == null) return;

        ClusterNode targetNode = findNode(info.nodeId());
        if (targetNode != null) {
            targetNode.send(new ClusterMessage("REQUEST_JOIN_ROOM", config.getNodeId(), new String[]{
                roomId, player.getSessionId(), player.getDisplayName()
            }));
        }
    }

    public void forwardCommand(Player player, String roomId, String cmdLine) {
        RemoteRoomInfo info = remoteRooms.get(roomId);
        if (info == null) return;

        ClusterNode targetNode = findNode(info.nodeId());
        if (targetNode != null) {
            targetNode.send(new ClusterMessage("FORWARD_PLAYER_CMD", config.getNodeId(), new String[]{
                roomId, player.getSessionId(), cmdLine
            }));
        }
    }

    public boolean routeWhisper(String targetName, String senderName, String text) {
        String targetNodeId = remotePlayers.get(targetName.toLowerCase());
        if (targetNodeId != null) {
            ClusterNode node = findNode(targetNodeId);
            if (node != null) {
                node.send(new ClusterMessage("WHISPER_ROUTE", config.getNodeId(), new String[]{
                    targetName, senderName, text
                }));
                return true;
            }
        }
        return false;
    }

    public void syncLocalRoom(Room room) {
        if (!config.isEnabled()) return;
        broadcast(new ClusterMessage("ROOM_SYNC", config.getNodeId(), new String[]{
            room.getRoomId(), room.getName(), room.getOwnerSessionId(),
            String.valueOf(room.getMaxPlayers()), room.getState().name()
        }));
    }

    public void destroyLocalRoom(String roomId) {
        if (!config.isEnabled()) return;
        broadcast(new ClusterMessage("ROOM_DESTROY", config.getNodeId(), new String[]{roomId}));
    }

    public void syncLocalPlayer(Player player) {
        if (!config.isEnabled() || player.getDisplayName() == null) return;
        broadcast(new ClusterMessage("PLAYER_SYNC", config.getNodeId(), new String[]{
            player.getDisplayName()
        }));
    }

    public void syncPlayerLeave(Player player) {
        if (!config.isEnabled() || player.getDisplayName() == null) return;
        broadcast(new ClusterMessage("PLAYER_LEAVE", config.getNodeId(), new String[]{
            player.getDisplayName()
        }));
        playerProxies.remove(player.getSessionId());
    }

    private ClusterNode findNode(String nodeId) {
        for (ClusterNode node : activeNodes) {
            if (nodeId.equals(node.getRemoteNodeId())) {
                return node;
            }
        }
        return null;
    }

    public List<RemoteRoomInfo> getRemoteRooms() {
        return new ArrayList<>(remoteRooms.values());
    }

    private void reconcileClusterState() {
        // Log topology health metrics periodically
        if (activeNodes.isEmpty()) return;
        Logger.logServer("Cluster topology: active connections count=" + activeNodes.size() +
                         " | Replicated rooms=" + remoteRooms.size() +
                         " | Replicated player registry size=" + remotePlayers.size());
    }

    public record RemoteRoomInfo(String roomId, String name, String ownerSessionId, int maxPlayers, RoomState state, String nodeId) {}
}
