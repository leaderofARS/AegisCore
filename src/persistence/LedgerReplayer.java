package persistence;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Static utility class for replaying stored session ledgers.
 *
 * <p>Enables reading a past session's timeline and formatting it chronologically
 * with relative time offsets.
 */
public final class LedgerReplayer {

    private static final String SESSIONS_DIR = "logs/sessions";

    private LedgerReplayer() {}

    /**
     * Reads a session ledger file from disk and returns its raw lines.
     *
     * @param roomId the room ID to replay
     * @return list of lines, or empty list if the file is not found or cannot be read
     */
    public static List<String> replay(String roomId) {
        File file = new File(SESSIONS_DIR, roomId + ".ledger");
        if (!file.exists()) {
            return Collections.emptyList();
        }

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            // Silently return what we have or empty
        }
        return lines;
    }

    /**
     * Prints the session replay to the given stream, parsing timestamps to print T+ millisecond offsets.
     *
     * @param roomId the room ID to print
     * @param out    the destination stream (e.g. System.out)
     */
    public static void printReplay(String roomId, PrintStream out) {
        List<String> lines = replay(roomId);
        if (lines.isEmpty()) {
            out.println("[REPLAY] No ledger found for room " + roomId);
            return;
        }

        out.println("=== Timeline Replay for Room: " + roomId + " ===");
        Long firstEpochMs = null;

        for (String line : lines) {
            if (line.startsWith("===")) {
                out.println(line);
                continue;
            }

            int endBracket = line.indexOf(']');
            if (line.startsWith("[") && endBracket > 0) {
                String tsStr = line.substring(1, endBracket);
                try {
                    Instant inst = Instant.parse(tsStr);
                    long epochMs = inst.toEpochMilli();
                    if (firstEpochMs == null) {
                        firstEpochMs = epochMs;
                    }
                    long offsetMs = epochMs - firstEpochMs;
                    out.printf("T+%6dms %s%n", offsetMs, line.substring(endBracket + 1).trim());
                } catch (Exception ex) {
                    out.println(line);
                }
            } else {
                out.println(line);
            }
        }
    }
}
