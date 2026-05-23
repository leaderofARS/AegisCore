import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Centralised, file-backed logging utility for the AegisCore multi-threaded system.
 *
 * <p>This class is a pure static utility: it holds no per-instance state and cannot be
 * instantiated. Every component in the system (server, client-handler, registry, client)
 * owns a dedicated log file so that log streams can be read and redirected independently
 * without interleaving noise from other subsystems.
 *
 * <p><b>Thread safety:</b> The private {@link #writeToFile} method is {@code static
 * synchronized}, which acquires the {@code Logger} <em>class-object monitor</em> before
 * performing any I/O. This is a deliberate coarse global lock: log writes are infrequent,
 * and strict ordering across all files is worth more than the marginal throughput gain of
 * per-file locking. All public entry points delegate to {@code writeToFile}, so they
 * inherit the same guarantee.
 *
 * <p><b>Output routing:</b>
 * <ul>
 *   <li>{@code INFO}  entries are written to {@link System#out} (white / default terminal colour).
 *   <li>{@code ERROR} entries are written to {@link System#err} (red in most terminals; can be
 *       separated from normal output when stdout and stderr are redirected to different sinks).
 * </ul>
 *
 * <p><b>Log files (all under {@code logs/}):</b>
 * <pre>
 *   logs/Server.log
 *   logs/ClientHandler.log
 *   logs/Registry.log
 *   logs/ClientID.log
 * </pre>
 *
 * <p><b>Design pattern:</b> Static utility class (non-instantiable). The {@code logs/}
 * directory is created exactly once at class-load time via a {@code static} initialiser.
 */
public class Logger {

    // -------------------------------------------------------------------------
    // CONSTANTS
    // -------------------------------------------------------------------------

    /**
     * Relative path to the directory that holds all log files.
     *
     * <p>Using a relative path anchors the logs directory to the JVM working directory,
     * keeping the layout self-contained and portable across environments.
     */
    private static final String LOG_DIR = "logs";

    /**
     * Timestamp formatter shared by every log entry.
     *
     * <p>{@link DateTimeFormatter} instances are immutable and thread-safe after
     * construction, so a single static constant is safe to reuse across concurrent callers.
     * The millisecond field ({@code .SSS}) gives enough resolution to distinguish rapid
     * successive events without the overhead of nanosecond precision.
     */
    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    // -------------------------------------------------------------------------
    // STATIC INITIALISER — directory bootstrap
    // -------------------------------------------------------------------------

    static {
        File dir = new File(LOG_DIR);
        if (!dir.exists()) {
            // mkdirs() creates the full ancestor chain in one call, so the logs/
            // directory will be created even if intermediate parent directories are
            // also missing (e.g. in a freshly unpacked distribution).
            dir.mkdirs();
        }
    }

    // -------------------------------------------------------------------------
    // CORE WRITE PRIMITIVE
    // -------------------------------------------------------------------------

    /**
     * Formats and persists a single log entry to the named file, then echoes it to the
     * appropriate standard stream.
     *
     * <p>The entry format is:
     * <pre>
     *   [yyyy-MM-dd HH:mm:ss.SSS] [LEVEL] message
     * </pre>
     *
     * <p>The method is {@code static synchronized}: it holds the {@code Logger}
     * class-object monitor for the duration of the call so that concurrent threads
     * cannot interleave partial log entries in the same file.
     *
     * <p>The {@link PrintWriter} is opened in <em>append</em> mode and wrapped in a
     * try-with-resources block, which guarantees the underlying buffer is flushed and the
     * file descriptor released even when an exception is thrown mid-write.
     *
     * <p>If the write itself fails (e.g. disk full, permission denied), the error is
     * reported directly to {@link System#err}. Falling back to console output rather than
     * re-invoking {@code writeToFile} avoids infinite recursion while still surfacing the
     * failure.
     *
     * @param filename the bare filename (no path) of the target log file within
     *                 {@value #LOG_DIR}; must not be {@code null}
     * @param level    the severity label to embed in the entry, e.g. {@code "INFO"} or
     *                 {@code "ERROR"}; must not be {@code null}
     * @param message  the human-readable log message; must not be {@code null}
     */
    private static synchronized void writeToFile(String filename, String level, String message) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String logEntry  = String.format("[%s] [%s] %s", timestamp, level, message);

        // Route to the correct standard stream so that ERROR entries appear in red on
        // ANSI-capable terminals and can be captured separately when stdout/stderr are
        // redirected to different sinks (e.g. server.log vs server.err in systemd).
        if ("ERROR".equals(level)) {
            System.err.println(logEntry);
        } else {
            System.out.println(logEntry);
        }

        File file = new File(LOG_DIR, filename);
        // Append mode (second argument = true) preserves all prior entries across JVM
        // restarts. try-with-resources ensures flush + close even if println() throws.
        try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
            writer.println(logEntry);
        } catch (IOException e) {
            // Do NOT call writeToFile() here — that would recurse infinitely if the disk
            // or filesystem is in a bad state.  Printing directly to System.err is the
            // safest fallback that is guaranteed not to involve file I/O.
            System.err.println("[ERROR] Failed to write to log file " + filename + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // SERVER LOGGING
    // -------------------------------------------------------------------------

    /**
     * Logs an informational message to the server log file ({@code logs/Server.log}).
     *
     * <p>Use this method for normal server lifecycle events such as start-up, shutdown,
     * and accepted connections.
     *
     * @param message the informational message to record; must not be {@code null}
     */
    public static void logServer(String message) {
        writeToFile("Server.log", "INFO", message);
    }

    /**
     * Logs an error message to the server log file ({@code logs/Server.log}).
     *
     * <p>The entry is also echoed to {@link System#err}, making it visible in terminals
     * that highlight the error stream in red.
     *
     * @param message the error description to record; must not be {@code null}
     */
    public static void logServerError(String message) {
        writeToFile("Server.log", "ERROR", message);
    }

    // -------------------------------------------------------------------------
    // CLIENT-HANDLER LOGGING
    // -------------------------------------------------------------------------

    /**
     * Logs an informational message to the client-handler log file
     * ({@code logs/ClientHandler.log}).
     *
     * <p>Use this method for events related to a specific client connection, such as
     * message receipt or graceful disconnection.
     *
     * @param message the informational message to record; must not be {@code null}
     */
    public static void logClientHandler(String message) {
        writeToFile("ClientHandler.log", "INFO", message);
    }

    /**
     * Logs an error message to the client-handler log file
     * ({@code logs/ClientHandler.log}).
     *
     * <p>The entry is also echoed to {@link System#err}.
     *
     * @param message the error description to record; must not be {@code null}
     */
    public static void logClientHandlerError(String message) {
        writeToFile("ClientHandler.log", "ERROR", message);
    }

    // -------------------------------------------------------------------------
    // REGISTRY LOGGING
    // -------------------------------------------------------------------------

    /**
     * Logs an informational message to the registry log file ({@code logs/Registry.log}).
     *
     * <p>Use this method for events related to client registration, deregistration, and
     * lookup operations performed by the registry subsystem.
     *
     * @param message the informational message to record; must not be {@code null}
     */
    public static void logRegistry(String message) {
        writeToFile("Registry.log", "INFO", message);
    }

    /**
     * Logs an error message to the registry log file ({@code logs/Registry.log}).
     *
     * <p>The entry is also echoed to {@link System#err}.
     *
     * @param message the error description to record; must not be {@code null}
     */
    public static void logRegistryError(String message) {
        writeToFile("Registry.log", "ERROR", message);
    }

    // -------------------------------------------------------------------------
    // CLIENT LOGGING
    // -------------------------------------------------------------------------

    /**
     * Logs an informational message to the client log file ({@code logs/ClientID.log}).
     *
     * <p>Use this method for client-side events such as connection establishment, message
     * dispatch, and server responses.
     *
     * @param message the informational message to record; must not be {@code null}
     */
    public static void logClient(String message) {
        writeToFile("ClientID.log", "INFO", message);
    }

    /**
     * Logs an error message to the client log file ({@code logs/ClientID.log}).
     *
     * <p>The entry is also echoed to {@link System#err}.
     *
     * @param message the error description to record; must not be {@code null}
     */
    public static void logClientError(String message) {
        writeToFile("ClientID.log", "ERROR", message);
    }
}
