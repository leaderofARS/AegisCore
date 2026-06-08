package admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Static utility for authenticating admin-level commands.
 *
 * <p>Passwords are compared using SHA-256 digests and a constant-time comparison
 * ({@link MessageDigest#isEqual}) to prevent timing-based side-channel attacks.
 *
 * <p>Non-instantiable.
 */
public final class AdminAuthenticator {

    private AdminAuthenticator() {}

    /**
     * Returns {@code true} if the given password matches the configured admin password.
     *
     * @param password the plaintext password to verify
     * @return whether authentication succeeded
     */
    public static boolean authenticate(String password) {
        if (password == null) return false;
        byte[] given    = sha256(password);
        byte[] expected = sha256(ServerConfig.getInstance().getAdminPassword());
        return MessageDigest.isEqual(given, expected);
    }

    /**
     * Computes the hex-encoded SHA-256 digest of the input string.
     *
     * @param input the string to hash
     * @return lowercase hex string of the SHA-256 digest
     */
    public static String hashPassword(String input) {
        return HexFormat.of().formatHex(sha256(input));
    }

    private static byte[] sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
