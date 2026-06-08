package persistence;

import java.time.Instant;

/**
 * Hand-rolled JSON serializer for {@link PlayerProfile} records.
 *
 * <p>Avoids external dependencies (like Jackson or Gson) to keep AegisCore minimal and lightweight.
 */
public final class ProfileSerializer {

    private ProfileSerializer() {}

    /**
     * Serializes a player profile to a JSON string.
     *
     * @param p the player profile
     * @return JSON string
     */
    public static String toJson(PlayerProfile p) {
        if (p == null) return "{}";
        return String.format(
            "{\"displayName\":\"%s\",\"totalSessions\":%d,\"totalGamesStarted\":%d,\"totalChatMessages\":%d,\"firstSeen\":\"%s\",\"lastSeen\":\"%s\"}",
            escapeJson(p.displayName()),
            p.totalSessions(),
            p.totalGamesStarted(),
            p.totalChatMessages(),
            p.firstSeen().toString(),
            p.lastSeen().toString()
        );
    }

    /**
     * Deserializes a player profile from a JSON string.
     *
     * @param json the raw JSON string
     * @return the deserialized player profile
     * @throws IllegalArgumentException if the json string is invalid or incomplete
     */
    public static PlayerProfile fromJson(String json) {
        try {
            String displayName = extractString(json, "displayName");
            int totalSessions = extractInt(json, "totalSessions");
            int totalGamesStarted = extractInt(json, "totalGamesStarted");
            int totalChatMessages = extractInt(json, "totalChatMessages");
            String firstSeenStr = extractString(json, "firstSeen");
            String lastSeenStr = extractString(json, "lastSeen");

            if (displayName == null || firstSeenStr == null || lastSeenStr == null) {
                throw new IllegalArgumentException("Missing required fields in profile JSON");
            }

            Instant firstSeen = Instant.parse(firstSeenStr);
            Instant lastSeen = Instant.parse(lastSeenStr);

            return new PlayerProfile(displayName, totalSessions, totalGamesStarted, totalChatMessages, firstSeen, lastSeen);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse PlayerProfile from JSON: " + e.getMessage(), e);
        }
    }

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String extractString(String json, String field) {
        String pattern = "\"" + field + "\":\"";
        int start = json.indexOf(pattern);
        if (start == -1) return null;
        start += pattern.length();
        int end = json.indexOf("\"", start);
        if (end == -1) return null;
        String val = json.substring(start, end);
        return val.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static int extractInt(String json, String field) {
        String pattern = "\"" + field + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return 0;
        start += pattern.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (start == end) return 0;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
