package security;

/**
 * Static utility for sanitizing raw client input before command processing.
 *
 * <p>Prevents injection attacks, byte-stuffing, and excessively long inputs from
 * reaching the command router. All methods are null-safe.
 *
 * <p>Non-instantiable.
 */
public final class InputSanitizer {

    /** Maximum accepted line length in characters. */
    public static final int MAX_LINE_LENGTH = 512;

    private InputSanitizer() {}

    /**
     * Sanitizes a raw input line for command dispatch.
     * <ol>
     *   <li>Returns an empty string if the input is null or blank.</li>
     *   <li>Strips ASCII control characters (code points &lt; 32, except space 0x20).</li>
     *   <li>Truncates to {@value #MAX_LINE_LENGTH} characters.</li>
     *   <li>Trims leading and trailing whitespace.</li>
     * </ol>
     *
     * @param raw the raw line received from the socket
     * @return cleaned string, never null
     */
    public static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_LINE_LENGTH));
        for (char c : raw.toCharArray()) {
            if (sb.length() >= MAX_LINE_LENGTH) break;
            if (c >= 32 || c == '\t') sb.append(c); // keep printable + tab
        }
        return sb.toString().trim();
    }

    /**
     * Returns {@code false} if the raw line should be rejected before sanitization:
     * null, blank, or exceeding {@value #MAX_LINE_LENGTH} characters after trimming.
     *
     * @param raw the raw line from the socket
     * @return true if the line passes basic validation
     */
    public static boolean isValidLine(String raw) {
        if (raw == null || raw.isBlank()) return false;
        return raw.trim().length() <= MAX_LINE_LENGTH;
    }

    /**
     * Sanitizes a candidate player display name.
     * Strips any character that is not alphanumeric or underscore, then
     * truncates to 20 characters.
     *
     * @param raw the raw name token
     * @return a clean name candidate (may be empty if all chars were stripped)
     */
    public static String sanitizeName(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(20);
        for (char c : raw.toCharArray()) {
            if (sb.length() >= 20) break;
            if (Character.isLetterOrDigit(c) || c == '_') sb.append(c);
        }
        return sb.toString();
    }
}
