package cluster;

import admin.ConfigLoader;

/**
 * Configuration manager for the AegisCore Clustering Subsystem.
 *
 * <p>Loads clustering parameters from system properties or environment variables,
 * enabling nodes to discover and replicate lobby states with peers.
 */
public final class ClusterConfig {

    private static final ClusterConfig instance = new ClusterConfig();

    private final boolean enabled;
    private final String nodeId;
    private final int port;
    private final String peers; // Comma-separated list of "host:port"

    private ClusterConfig() {
        this.enabled = ConfigLoader.getBoolean("cluster.enabled", false);
        this.nodeId = ConfigLoader.getString("cluster.nodeId", "node-1");
        this.port = ConfigLoader.getInt("cluster.port", 6000);
        this.peers = ConfigLoader.getString("cluster.peers", "");
    }

    public static ClusterConfig getInstance() {
        return instance;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getNodeId() {
        return nodeId;
    }

    public int getPort() {
        return port;
    }

    public String getPeers() {
        return peers;
    }

    @Override
    public String toString() {
        return "ClusterConfig{" +
                "enabled=" + enabled +
                ", nodeId='" + nodeId + '\'' +
                ", port=" + port +
                ", peers='" + peers + '\'' +
                '}';
    }
}
