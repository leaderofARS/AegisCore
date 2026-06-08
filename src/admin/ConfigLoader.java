package admin;

/**
 * Static utility for reading AegisCore configuration values from system properties
 * and environment variables, with safe defaults.
 *
 * <p>Lookup order for a key {@code "port"}:
 * <ol>
 *   <li>System property: {@code -Daegiscore.port=5000}</li>
 *   <li>Environment variable: {@code AEGISCORE_PORT=5000}</li>
 *   <li>Supplied default value</li>
 * </ol>
 *
 * <p>Non-instantiable.
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    /**
     * Reads an integer configuration value.
     *
     * @param key        configuration key (e.g., {@code "port"})
     * @param defaultVal fallback value if the key is absent or unparseable
     * @return resolved integer value
     */
    public static int getInt(String key, int defaultVal) {
        String raw = resolve(key);
        if (raw == null) return defaultVal;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    /**
     * Reads a string configuration value.
     *
     * @param key        configuration key
     * @param defaultVal fallback value if the key is absent
     * @return resolved string value
     */
    public static String getString(String key, String defaultVal) {
        String raw = resolve(key);
        return raw == null ? defaultVal : raw.trim();
    }

    /**
     * Reads a boolean configuration value.
     *
     * @param key        configuration key
     * @param defaultVal fallback value if the key is absent
     * @return resolved boolean value
     */
    public static boolean getBoolean(String key, boolean defaultVal) {
        String raw = resolve(key);
        if (raw == null) return defaultVal;
        return Boolean.parseBoolean(raw.trim());
    }

    // -----------------------------------------------------------------------

    /** Resolves a key via system property then environment variable. */
    private static String resolve(String key) {
        // System property: aegiscore.<key>
        String sysProp = System.getProperty("aegiscore." + key);
        if (sysProp != null) return sysProp;
        // Env var: AEGISCORE_<KEY>
        String envKey = "AEGISCORE_" + key.toUpperCase().replace('.', '_');
        return System.getenv(envKey);
    }
}
