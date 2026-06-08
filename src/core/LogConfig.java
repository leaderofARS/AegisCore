package core;

/**
 * Configuration for the AegisCore logging subsystem.
 *
 * <p>Controls the minimum severity threshold, file rotation size, and whether
 * the async writer is used. Reads values from {@link admin.ConfigLoader} at
 * class-load time so configuration changes require a server restart.
 *
 * <p>Singleton, eagerly initialised.
 */
public final class LogConfig {

    private static final LogConfig INSTANCE = new LogConfig();

    private final LogLevel minimumLevel;
    private final long     maxFileSizeBytes;
    private final boolean  asyncEnabled;
    private final boolean  jsonMode;

    private LogConfig() {
        String levelStr   = admin.ConfigLoader.getString("log.level",           "INFO");
        long   maxSizeMb  = admin.ConfigLoader.getInt  ("log.rotation.mb",      10);
        asyncEnabled      = admin.ConfigLoader.getBoolean("log.async",           true);
        jsonMode          = admin.ConfigLoader.getBoolean("log.json",            false);

        this.minimumLevel     = LogLevel.parse(levelStr);
        this.maxFileSizeBytes = maxSizeMb * 1024L * 1024L;
    }

    /** Returns the singleton {@code LogConfig}. */
    public static LogConfig getInstance() { return INSTANCE; }

    /**
     * Returns the minimum {@link LogLevel} that will be written.
     * Messages below this threshold are silently discarded.
     *
     * @return configured threshold level
     */
    public LogLevel minimumLevel() { return minimumLevel; }

    /**
     * Returns the maximum file size in bytes before a log file is rotated.
     *
     * @return rotation threshold in bytes
     */
    public long maxFileSizeBytes() { return maxFileSizeBytes; }

    /**
     * Returns {@code true} if log entries should be written asynchronously
     * via the background {@link AsyncLogWriter} queue.
     *
     * @return whether async mode is enabled
     */
    public boolean asyncEnabled() { return asyncEnabled; }

    /**
     * Returns {@code true} if log entries should be emitted in JSON format
     * for consumption by log aggregators (e.g., Elasticsearch, Loki).
     *
     * @return whether JSON output mode is enabled
     */
    public boolean jsonMode() { return jsonMode; }

    @Override
    public String toString() {
        return String.format("LogConfig{level=%s, rotationMb=%d, async=%b, json=%b}",
            minimumLevel, maxFileSizeBytes / (1024 * 1024), asyncEnabled, jsonMode);
    }
}
