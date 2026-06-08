package core;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background log-writer that decouples I/O from caller threads.
 *
 * <p>Log entries submitted via {@link #enqueue(LogEntry)} are placed on a
 * {@link LinkedBlockingQueue} and flushed to the appropriate file by a single
 * dedicated daemon thread. This prevents slow disk writes from blocking
 * command-processing threads.
 *
 * <p>Files are rotated when they exceed {@link #MAX_FILE_BYTES}: the current
 * file is renamed to {@code <name>.1}, and writing continues on a fresh file.
 *
 * <p>Call {@link #start()} once from the class that initialises logging.
 * Call {@link #shutdown()} during graceful server shutdown to drain the queue.
 */
public final class AsyncLogWriter {

    private static final AsyncLogWriter INSTANCE = new AsyncLogWriter();

    /** Maximum file size before rotation (10 MB). */
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final BlockingQueue<LogEntry> queue   = new LinkedBlockingQueue<>(8_192);
    private final AtomicBoolean           running = new AtomicBoolean(false);
    private Thread                        worker;

    private AsyncLogWriter() {}

    /** Returns the singleton {@code AsyncLogWriter}. */
    public static AsyncLogWriter getInstance() { return INSTANCE; }

    /**
     * Starts the background writer thread. Idempotent — safe to call multiple times.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            worker = Thread.ofVirtual().name("AsyncLogWriter").unstarted(this::loop);
            worker.setDaemon(true);
            worker.start();
        }
    }

    /**
     * Submits a log entry for asynchronous writing.
     *
     * <p>If the queue is full (back-pressure scenario) the entry is written
     * synchronously on the caller thread as a fallback so no log lines are dropped.
     *
     * @param entry the entry to persist
     */
    public void enqueue(LogEntry entry) {
        if (!queue.offer(entry)) {
            // Fallback: write synchronously so no log line is lost
            writeNow(entry);
        }
    }

    /**
     * Drains remaining entries and stops the writer thread.
     * Blocks until the queue is empty or a 5-second timeout expires.
     */
    public void shutdown() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
        }
        // Drain remaining entries
        LogEntry entry;
        while ((entry = queue.poll()) != null) {
            writeNow(entry);
        }
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private void loop() {
        while (running.get() || !queue.isEmpty()) {
            try {
                LogEntry entry = queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (entry != null) {
                    writeNow(entry);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Drain and exit
                LogEntry e2;
                while ((e2 = queue.poll()) != null) { writeNow(e2); }
                break;
            }
        }
    }

    private void writeNow(LogEntry entry) {
        String formatted = String.format("[%s] [%s] %s",
            LocalDateTime.now().format(FORMATTER),
            entry.level().name(),
            entry.message());

        (entry.level() == LogLevel.ERROR ? System.err : System.out).println(formatted);

        File logFile = new File("logs", entry.filename());
        rotateIfNeeded(logFile);

        try (PrintWriter w = new PrintWriter(new FileWriter(logFile, true))) {
            w.println(formatted);
        } catch (IOException ex) {
            System.err.println("[AsyncLogWriter] Cannot write to " + entry.filename() + ": " + ex.getMessage());
        }
    }

    /** Renames the file to {@code <name>.1} if its size exceeds {@link #MAX_FILE_BYTES}. */
    private void rotateIfNeeded(File file) {
        if (file.exists() && file.length() >= MAX_FILE_BYTES) {
            File rotated = new File(file.getParent(), file.getName() + ".1");
            rotated.delete(); // remove old rotated file
            file.renameTo(rotated);
        }
    }

    // -----------------------------------------------------------------------
    // Entry record
    // -----------------------------------------------------------------------

    /**
     * An immutable log entry waiting to be written.
     *
     * @param filename target log filename (relative to {@code logs/})
     * @param level    log severity level
     * @param message  formatted log message body
     */
    public record LogEntry(String filename, LogLevel level, String message) {}
}
