package network;

import core.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Virtual-thread-based client connection pool for AegisCore.
 *
 * <p>Uses Java 21+ virtual threads ({@link Executors#newVirtualThreadPerTaskExecutor()})
 * to schedule each client handler. Virtual threads are extremely lightweight — the JVM
 * can sustain millions of them simultaneously, eliminating the one-OS-thread-per-client
 * bottleneck of the original implementation.
 *
 * <p>Singleton. Thread-safe.
 */
public final class ClientThreadPool {

    private static final ClientThreadPool INSTANCE = new ClientThreadPool();

    private final ExecutorService executor;
    private final AtomicLong      submitted = new AtomicLong(0);

    private ClientThreadPool() {
        // Virtual thread per task — cheapest possible per-connection concurrency in Java 21+
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        Logger.logServer("ClientThreadPool: virtual-thread executor initialized.");
    }

    /** Returns the singleton {@code ClientThreadPool}. */
    public static ClientThreadPool getInstance() {
        return INSTANCE;
    }

    /**
     * Submits a client handler task for execution on a named virtual thread.
     *
     * @param task       the {@link Runnable} representing the client session
     * @param threadName a human-readable name used in thread-local logging
     */
    public void submit(Runnable task, String threadName) {
        submitted.incrementAndGet();
        executor.submit(() -> {
            Thread.currentThread().setName(threadName);
            try {
                task.run();
            } catch (Throwable t) {
                Logger.logClientHandlerError("Uncaught exception on " + threadName + ": " + t.getMessage());
            }
        });
    }

    /**
     * Returns the cumulative number of tasks ever submitted to this pool.
     *
     * @return total submitted task count
     */
    public long getSubmittedCount() {
        return submitted.get();
    }

    /**
     * Initiates an orderly shutdown. Waits up to {@code timeoutMs} milliseconds for
     * running tasks to complete, then forces termination.
     *
     * @param timeoutMs maximum wait time in milliseconds
     */
    public void shutdown(long timeoutMs) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        Logger.logServer("ClientThreadPool: shutdown complete.");
    }
}
