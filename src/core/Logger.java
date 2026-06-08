package core;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Centralised, file-backed logging utility for AegisCore.
 *
 * <p>Each subsystem writes to its own dedicated log file under {@code logs/}:
 * <ul>
 *   <li>{@code Server.log} — server lifecycle events</li>
 *   <li>{@code ClientHandler.log} — per-player connection events</li>
 *   <li>{@code Registry.log} — room, player, and matchmaking events</li>
 *   <li>{@code ClientID.log} — test-client events</li>
 * </ul>
 *
 * <p>When {@link LogConfig#asyncEnabled()} is {@code true} (the default), entries
 * are handed to {@link AsyncLogWriter} for background I/O. When async is disabled
 * each write holds the file monitor and writes synchronously.
 *
 * <p>Entries whose severity is below the configured {@link LogConfig#minimumLevel()}
 * are discarded without any I/O.
 *
 * <p>Non-instantiable static utility.
 */
public final class Logger {

    private static final String            LOG_DIR   = "logs";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Object SERVER_LOCK   = new Object();
    private static final Object HANDLER_LOCK  = new Object();
    private static final Object REGISTRY_LOCK = new Object();
    private static final Object CLIENT_LOCK   = new Object();

    static {
        File dir = new File(LOG_DIR);
        if (!dir.exists()) { dir.mkdirs(); }
        // Start the async writer daemon
        AsyncLogWriter.getInstance().start();
    }

    private Logger() {}

    // -----------------------------------------------------------------------
    // Server log
    // -----------------------------------------------------------------------

    /** Logs an informational entry to {@code Server.log}. */
    public static void logServer(String message) {
        log("Server.log", LogLevel.INFO, message, SERVER_LOCK);
    }

    /** Logs an error entry to {@code Server.log}. */
    public static void logServerError(String message) {
        log("Server.log", LogLevel.ERROR, message, SERVER_LOCK);
    }

    // -----------------------------------------------------------------------
    // ClientHandler log
    // -----------------------------------------------------------------------

    /** Logs an informational entry to {@code ClientHandler.log}. */
    public static void logClientHandler(String message) {
        log("ClientHandler.log", LogLevel.INFO, message, HANDLER_LOCK);
    }

    /** Logs an error entry to {@code ClientHandler.log}. */
    public static void logClientHandlerError(String message) {
        log("ClientHandler.log", LogLevel.ERROR, message, HANDLER_LOCK);
    }

    // -----------------------------------------------------------------------
    // Registry log
    // -----------------------------------------------------------------------

    /** Logs an informational entry to {@code Registry.log}. */
    public static void logRegistry(String message) {
        log("Registry.log", LogLevel.INFO, message, REGISTRY_LOCK);
    }

    /** Logs an error entry to {@code Registry.log}. */
    public static void logRegistryError(String message) {
        log("Registry.log", LogLevel.ERROR, message, REGISTRY_LOCK);
    }

    // -----------------------------------------------------------------------
    // Client (test client) log
    // -----------------------------------------------------------------------

    /** Logs an informational entry to {@code ClientID.log}. */
    public static void logClient(String message) {
        log("ClientID.log", LogLevel.INFO, message, CLIENT_LOCK);
    }

    /** Logs an error entry to {@code ClientID.log}. */
    public static void logClientError(String message) {
        log("ClientID.log", LogLevel.ERROR, message, CLIENT_LOCK);
    }

    // -----------------------------------------------------------------------
    // Generic log entry point (level-aware, async-aware)
    // -----------------------------------------------------------------------

    /**
     * Core logging method. Applies the configured minimum-level filter, then
     * routes to the async writer or the synchronous file writer based on config.
     *
     * @param filename  target log file name (relative to {@code logs/})
     * @param level     severity of this entry
     * @param message   human-readable message body
     * @param fileLock  per-file monitor for synchronous writes
     */
    private static void log(String filename, LogLevel level, String message, Object fileLock) {
        LogConfig config = LogConfig.getInstance();
        if (!level.isAtLeast(config.minimumLevel())) { return; }

        if (config.asyncEnabled()) {
            AsyncLogWriter.getInstance().enqueue(
                new AsyncLogWriter.LogEntry(filename, level, message));
        } else {
            synchronized (fileLock) {
                writeToFile(filename, level, message);
            }
        }
    }

    /**
     * Synchronous file write used when async mode is disabled, or as a fallback.
     */
    private static void writeToFile(String filename, LogLevel level, String message) {
        String entry = String.format("[%s] [%s] %s",
            LocalDateTime.now().format(FORMATTER), level.name(), message);
        (level == LogLevel.ERROR ? System.err : System.out).println(entry);
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(LOG_DIR, filename), true))) {
            w.println(entry);
        } catch (IOException e) {
            System.err.println("[ERROR] Cannot write to " + filename + ": " + e.getMessage());
        }
    }
}
