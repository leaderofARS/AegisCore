package core;

/**
 * Log severity levels for AegisCore, ordered from least to most severe.
 */
public enum LogLevel {
    DEBUG(0), INFO(1), WARN(2), ERROR(3);

    private final int value;

    LogLevel(int value) { this.value = value; }

    /**
     * Returns {@code true} if this level is at least as severe as {@code threshold}.
     *
     * @param threshold the minimum level to compare against
     * @return whether this level should be emitted under the given threshold
     */
    public boolean isAtLeast(LogLevel threshold) {
        return this.value >= threshold.value;
    }

    /**
     * Parses a level name (case-insensitive) returning {@code INFO} on unknown input.
     *
     * @param name the level name string
     * @return the matching level or {@code INFO}
     */
    public static LogLevel parse(String name) {
        if (name == null) return INFO;
        return switch (name.toUpperCase()) {
            case "DEBUG" -> DEBUG;
            case "WARN"  -> WARN;
            case "ERROR" -> ERROR;
            default      -> INFO;
        };
    }
}
