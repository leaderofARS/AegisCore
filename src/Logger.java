import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logger — Level 2.4: Synchronization Hardening
 *
 * Thread-safe centralized logging system.
 * Writes timestamped entries to both the console and component-specific log files
 * stored in the /logs directory.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * THREAD SAFETY
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   writeToFile() is the single write path. It is synchronized on the Logger
 *   CLASS object (static synchronized method). This means only ONE thread
 *   can write a log entry at a time — across ALL log files.
 *
 *   This is intentionally a coarse global lock: log writes are infrequent
 *   relative to business logic, and correct ordering of log output is more
 *   valuable than marginal throughput gains from per-file locking.
 *
 *   If log throughput becomes a bottleneck at high concurrency, the fix is
 *   a dedicated logging thread with a BlockingQueue — that is a Level 2.5+
 *   concern and will be addressed when stress profiling demands it.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * LOG FILES
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   logs/Server.log          — server socket lifecycle events
 *   logs/ClientHandler.log   — per-client message events and thread states
 *   logs/Registry.log        — client registration and deregistration events (NEW 2.4)
 *   logs/ClientID.log        — client-side diagnostic output
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * LEVEL 2.4 ADDITION
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   Added logRegistry() and logRegistryError() — routes SharedClientRegistry
 *   output through this synchronized Logger instead of raw System.out.println().
 *
 *   WHY THIS MATTERS:
 *     Registry events (connect/disconnect) were previously written with
 *     System.out.println() directly. Under concurrent load, multiple threads
 *     calling println() simultaneously produce interleaved console lines:
 *
 *         [INFO] Client [INFO] disconnected: /127.0.0.1:5432connected: /127.0.0.1:5433
 *
 *     Routing through the synchronized Logger serializes all console output
 *     and guarantees each log entry appears as one complete, intact line.
 */
public class Logger
{
    // ─────────────────────────────────────────────────────────────────────────
    // CONFIGURATION
    // ─────────────────────────────────────────────────────────────────────────

    /** Directory where all log files are written. Relative to working directory. */
    private static final String LOG_DIR = "logs";

    /** Timestamp format used in every log entry. Millisecond precision for debugging. */
    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");


    // ─────────────────────────────────────────────────────────────────────────
    // STATIC INITIALIZER — create log directory on first class load
    // ─────────────────────────────────────────────────────────────────────────

    static {
        // Runs once when the Logger class is first loaded by the JVM.
        // Creates the logs/ directory if it does not already exist.
        // mkdirs() creates the full path including parent directories.
        File dir = new File(LOG_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // CORE WRITE METHOD — THE SYNCHRONIZED CRITICAL SECTION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Writes a timestamped log entry to the console and to a specific log file.
     *
     * SYNCHRONIZATION:
     *   "static synchronized" acquires the monitor on the Logger CLASS object
     *   (not an instance). This is a class-level lock — effectively a global
     *   lock for all Logger calls. Only one thread can write a log entry at a time.
     *
     *   This prevents console output interleaving across concurrent threads and
     *   ensures each line in the log file is a complete, intact entry.
     *
     * @param filename  Name of the log file within LOG_DIR (e.g., "Server.log").
     * @param level     Log level label: "INFO" or "ERROR".
     * @param message   The message body to record.
     */
    private static synchronized void writeToFile(String filename, String level, String message)
    {
        // Build the complete log entry string with timestamp and level prefix.
        String timestamp = LocalDateTime.now().format(FORMATTER);
        String logEntry  = String.format("[%s] [%s] %s", timestamp, level, message);

        // ── CONSOLE OUTPUT ───────────────────────────────────────────────────
        // ERROR goes to stderr (System.err) so it appears in red in most terminals
        // and can be separated from normal stdout in piped/redirected outputs.
        if ("ERROR".equals(level)) {
            System.err.println(logEntry);
        } else {
            System.out.println(logEntry);
        }

        // ── FILE OUTPUT ──────────────────────────────────────────────────────
        // Opens the file in APPEND mode (second arg = true).
        // try-with-resources guarantees the PrintWriter is closed even on exception,
        // which flushes the buffer and releases the file descriptor.
        File file = new File(LOG_DIR, filename);
        try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
            writer.println(logEntry);
        } catch (IOException e) {
            // Cannot use Logger here (would recurse). Fall back to stderr directly.
            System.err.println("[ERROR] Failed to write to log file " + filename + ": " + e.getMessage());
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // SERVER LOG METHODS — logs/Server.log
    // ─────────────────────────────────────────────────────────────────────────

    /** Logs an INFO-level event from the server accept loop to logs/Server.log. */
    public static void logServer(String message) {
        writeToFile("Server.log", "INFO", message);
    }

    /** Logs an ERROR-level event from the server accept loop to logs/Server.log. */
    public static void logServerError(String message) {
        writeToFile("Server.log", "ERROR", message);
    }


    // ─────────────────────────────────────────────────────────────────────────
    // CLIENT HANDLER LOG METHODS — logs/ClientHandler.log
    // ─────────────────────────────────────────────────────────────────────────

    /** Logs an INFO-level event from a ClientHandler thread to logs/ClientHandler.log. */
    public static void logClientHandler(String message) {
        writeToFile("ClientHandler.log", "INFO", message);
    }

    /** Logs an ERROR-level event from a ClientHandler thread to logs/ClientHandler.log. */
    public static void logClientHandlerError(String message) {
        writeToFile("ClientHandler.log", "ERROR", message);
    }


    // ─────────────────────────────────────────────────────────────────────────
    // REGISTRY LOG METHODS — logs/Registry.log  (NEW — Level 2.4)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Logs an INFO-level event from SharedClientRegistry to logs/Registry.log.
     *
     * Level 2.4 addition: replaces raw System.out.println() calls in the registry.
     * Routes all registry lifecycle output through the synchronized Logger so that
     * connect/disconnect events are written as complete, uninterleaved log lines
     * even under high concurrent connection churn.
     */
    public static void logRegistry(String message) {
        writeToFile("Registry.log", "INFO", message);
    }

    /**
     * Logs an ERROR-level event from SharedClientRegistry to logs/Registry.log.
     *
     * Used for broadcast delivery failures and other registry-level errors.
     */
    public static void logRegistryError(String message) {
        writeToFile("Registry.log", "ERROR", message);
    }


    // ─────────────────────────────────────────────────────────────────────────
    // CLIENT LOG METHODS — logs/ClientID.log
    // ─────────────────────────────────────────────────────────────────────────

    /** Logs an INFO-level event from the Client (client-side) to logs/ClientID.log. */
    public static void logClient(String message) {
        writeToFile("ClientID.log", "INFO", message);
    }

    /** Logs an ERROR-level event from the Client (client-side) to logs/ClientID.log. */
    public static void logClientError(String message) {
        writeToFile("ClientID.log", "ERROR", message);
    }
}
