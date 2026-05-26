import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Centralised, file-backed logging utility for the AegisCore server.
 *
 * <p>Each subsystem writes to its own dedicated log file under {@code logs/}:
 * <ul>
 *   <li>{@code Server.log} — server lifecycle events</li>
 *   <li>{@code ClientHandler.log} — per-client connection events</li>
 *   <li>{@code Registry.log} — broadcast and registration events</li>
 *   <li>{@code ClientID.log} — client-side events</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> Each log file is protected by its own dedicated {@code Object}
 * monitor ({@link #SERVER_LOCK}, {@link #HANDLER_LOCK}, {@link #REGISTRY_LOCK},
 * {@link #CLIENT_LOCK}). Writes to different files proceed in parallel; only concurrent
 * writes to the <em>same</em> file are serialized.
 *
 * <p>{@code INFO} entries are echoed to {@link System#out}; {@code ERROR} entries to
 * {@link System#err}. The {@code logs/} directory is created at class-load time if absent.
 *
 * <p>This class is a non-instantiable static utility.
 */
public class Logger {

    private static final String              LOG_DIR   = "logs";
    private static final DateTimeFormatter   FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Object SERVER_LOCK   = new Object();
    private static final Object HANDLER_LOCK  = new Object();
    private static final Object REGISTRY_LOCK = new Object();
    private static final Object CLIENT_LOCK   = new Object();

    static {
        File dir = new File(LOG_DIR);
        if (!dir.exists()) { dir.mkdirs(); }
    }

    private Logger() {}

    /**
     * Formats and appends a single log entry to the specified file, then echoes it to
     * the appropriate standard stream. Must be called while the caller holds the
     * corresponding per-file lock.
     *
     * @param filename bare filename within {@value #LOG_DIR}
     * @param level    severity label, e.g. {@code "INFO"} or {@code "ERROR"}
     * @param message  log message text
     */
    private static void writeToFile(String filename, String level, String message) {
        String logEntry = String.format("[%s] [%s] %s", LocalDateTime.now().format(FORMATTER), level, message);

        if ("ERROR".equals(level)) {
            System.err.println(logEntry);
        } else {
            System.out.println(logEntry);
        }

        File file = new File(LOG_DIR, filename);
        try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
            writer.println(logEntry);
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to write to log file " + filename + ": " + e.getMessage());
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
