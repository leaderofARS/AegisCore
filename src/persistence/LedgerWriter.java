package persistence;

import core.Logger;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;

/**
 * Static utility class for persisting {@link SessionLedger} event timelines to flat files.
 *
 * <p>Writes to {@code logs/sessions/<roomId>.ledger}.
 */
public final class LedgerWriter {

    private static final String SESSIONS_DIR = "logs/sessions";

    private LedgerWriter() {}

    /**
     * Writes all events from the given ledger to a file under {@code logs/sessions/}.
     *
     * @param ledger the completed session ledger to write
     */
    public static void write(SessionLedger ledger) {
        if (ledger == null) return;

        File dir = new File(SESSIONS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        File file = new File(dir, ledger.getRoomId() + ".ledger");
        try (PrintWriter writer = new PrintWriter(new FileWriter(file, false))) {
            writer.printf("=== Session Ledger: %s | Events: %d | Closed: %s ===%n",
                ledger.getRoomId(), ledger.size(), Instant.now());
            for (SessionEvent event : ledger.getEvents()) {
                writer.println(event.toReplayLine());
            }
        } catch (IOException e) {
            Logger.logServerError("LedgerWriter failed to write ledger for room " +
                ledger.getRoomId() + ": " + e.getMessage());
        }
    }
}
