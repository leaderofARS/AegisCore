package admin;

/**
 * Central configuration singleton for the AegisCore server.
 *
 * <p>All hardcoded constants are replaced by values loaded via {@link ConfigLoader}.
 * Values are resolved from (in order): system properties → environment variables → defaults.
 *
 * <p>Eagerly initialized singleton — safe for publication to any thread.
 */
public final class ServerConfig {

    private static final ServerConfig INSTANCE = new ServerConfig();

    private final int     port;
    private final int     heartbeatIntervalSeconds;
    private final int     heartbeatTimeoutSeconds;
    private final int     maxConnectionsPerIp;
    private final int     rateLimitMaxBurst;
    private final int     rateLimitRefillPerSecond;
    private final int     matchmakingTimeoutSeconds;
    private final int     matchPlayersPerGame;
    private final int     maxRooms;
    private final String  adminPassword;
    private final String  logLevel;
    private final boolean metricsEnabled;
    private final int     metricsPort;

    private ServerConfig() {
        port                      = ConfigLoader.getInt    ("port",                        5000);
        heartbeatIntervalSeconds  = ConfigLoader.getInt    ("heartbeat.interval.seconds",  15);
        heartbeatTimeoutSeconds   = ConfigLoader.getInt    ("heartbeat.timeout.seconds",   5);
        maxConnectionsPerIp       = ConfigLoader.getInt    ("max.connections.per.ip",      10);
        rateLimitMaxBurst         = ConfigLoader.getInt    ("ratelimit.max.burst",         10);
        rateLimitRefillPerSecond  = ConfigLoader.getInt    ("ratelimit.refill.per.second", 5);
        matchmakingTimeoutSeconds = ConfigLoader.getInt    ("matchmaking.timeout.seconds", 30);
        matchPlayersPerGame       = ConfigLoader.getInt    ("match.players.per.game",      2);
        maxRooms                  = ConfigLoader.getInt    ("max.rooms",                   100);
        adminPassword             = ConfigLoader.getString ("admin.password",              "aegiscore-admin");
        logLevel                  = ConfigLoader.getString ("log.level",                   "INFO");
        metricsEnabled            = ConfigLoader.getBoolean("metrics.enabled",             true);
        metricsPort               = ConfigLoader.getInt    ("metrics.port",                8080);
    }

    /** Returns the singleton {@code ServerConfig} instance. */
    public static ServerConfig getInstance() { return INSTANCE; }

    public int     getPort()                     { return port; }
    public int     getHeartbeatIntervalSeconds()  { return heartbeatIntervalSeconds; }
    public int     getHeartbeatTimeoutSeconds()   { return heartbeatTimeoutSeconds; }
    public int     getMaxConnectionsPerIp()       { return maxConnectionsPerIp; }
    public int     getRateLimitMaxBurst()         { return rateLimitMaxBurst; }
    public int     getRateLimitRefillPerSecond()  { return rateLimitRefillPerSecond; }
    public int     getMatchmakingTimeoutSeconds() { return matchmakingTimeoutSeconds; }
    public int     getMatchPlayersPerGame()       { return matchPlayersPerGame; }
    public int     getMaxRooms()                  { return maxRooms; }
    public String  getAdminPassword()             { return adminPassword; }
    public String  getLogLevel()                  { return logLevel; }
    public boolean isMetricsEnabled()             { return metricsEnabled; }
    public int     getMetricsPort()               { return metricsPort; }

    @Override
    public String toString() {
        return String.format(
            "ServerConfig{port=%d, heartbeatInterval=%ds, maxConnPerIp=%d, " +
            "rateLimit=%d burst/%d/s, matchPlayers=%d, maxRooms=%d, metrics=%b:%d}",
            port, heartbeatIntervalSeconds, maxConnectionsPerIp,
            rateLimitMaxBurst, rateLimitRefillPerSecond,
            matchPlayersPerGame, maxRooms, metricsEnabled, metricsPort);
    }
}
