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
 * <p>Each log file is protected by its own {@code Object} monitor, allowing
 * concurrent writes to different files to proceed in parallel. Only writes
 * to the same file are serialised.
 *
 * <p>{@code INFO} entries echo to {@link System#out}; {@code ERROR} to {@link System#err}.
 * The {@code logs/} directory is created at class-load time if absent.
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
    }

    private Logger() {}

    private static void writeToFile(String filename, String level, String message) {
        String entry = String.format("[%s] [%s] %s", LocalDateTime.now().format(FORMATTER), level, message);
        ("ERROR".equals(level) ? System.err : System.out).println(entry);
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(LOG_DIR, filename), true))) {
            w.println(entry);
        } catch (IOException e) {
            System.err.println("[ERROR] Cannot write to " + filename + ": " + e.getMessage());
        }
    }

    /** Logs an informational entry to {@code Server.log}. */
    public static void logServer(String message) {
        synchronized (SERVER_LOCK) { writeToFile("Server.log", "INFO", message); }
    }

    /** Logs an error entry to {@code Server.log}. */
    public static void logServerError(String message) {
        synchronized (SERVER_LOCK) { writeToFile("Server.log", "ERROR", message); }
    }

    /** Logs an informational entry to {@code ClientHandler.log}. */
    public static void logClientHandler(String message) {
        synchronized (HANDLER_LOCK) { writeToFile("ClientHandler.log", "INFO", message); }
    }

    /** Logs an error entry to {@code ClientHandler.log}. */
    public static void logClientHandlerError(String message) {
        synchronized (HANDLER_LOCK) { writeToFile("ClientHandler.log", "ERROR", message); }
    }

    /** Logs an informational entry to {@code Registry.log}. */
    public static void logRegistry(String message) {
        synchronized (REGISTRY_LOCK) { writeToFile("Registry.log", "INFO", message); }
    }

    /** Logs an error entry to {@code Registry.log}. */
    public static void logRegistryError(String message) {
        synchronized (REGISTRY_LOCK) { writeToFile("Registry.log", "ERROR", message); }
    }

    /** Logs an informational entry to {@code ClientID.log}. */
    public static void logClient(String message) {
        synchronized (CLIENT_LOCK) { writeToFile("ClientID.log", "INFO", message); }
    }

    /** Logs an error entry to {@code ClientID.log}. */
    public static void logClientError(String message) {
        synchronized (CLIENT_LOCK) { writeToFile("ClientID.log", "ERROR", message); }
    }
}
