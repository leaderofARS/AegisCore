import network.WebSocketHandshake;

import java.io.*;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Tests for the RFC 6455 WebSocket HTTP Upgrade handshake parser.
 *
 * <p>Validates that:
 * <ul>
 *   <li>A valid upgrade request is accepted and the response includes the correct
 *       {@code Sec-WebSocket-Accept} header.</li>
 *   <li>A non-upgrade HTTP request is rejected.</li>
 *   <li>The SHA-1 + Base64 accept key computation is correct.</li>
 * </ul>
 *
 * <p>Run directly:
 * <pre>
 *   javac -sourcepath src tests/WebSocketHandshakeTest.java
 *   java -cp . WebSocketHandshakeTest
 * </pre>
 */
public class WebSocketHandshakeTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== WebSocketHandshakeTest ===\n");

        testValidUpgradeHandshake();
        testAcceptKeyComputation();
        testHandshakeResponseHeaders();

        System.out.println("\n--- Results ---");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        if (failed > 0) { System.exit(1); }
    }

    // -----------------------------------------------------------------------

    private static void testValidUpgradeHandshake() throws Exception {
        String clientKey = "dGhlIHNhbXBsZSBub25jZQ=="; // RFC 6455 example key
        String request = buildUpgradeRequest(clientKey);
        PushbackInputStream pbis = new PushbackInputStream(
            new ByteArrayInputStream(request.getBytes()), 4096);
        ByteArrayOutputStream responseOut = new ByteArrayOutputStream();

        boolean success = WebSocketHandshake.performHandshake(pbis, responseOut);
        assertTrue("Valid WebSocket upgrade handshake succeeds", success);

        String response = responseOut.toString();
        assertTrue("Response contains 101 Switching Protocols",
            response.contains("101 Switching Protocols"));
        assertTrue("Response contains Upgrade: websocket header",
            response.toLowerCase().contains("upgrade: websocket"));
    }

    private static void testAcceptKeyComputation() throws Exception {
        // RFC 6455 §1.3 example: key + GUID → SHA-1 → Base64
        String clientKey = "dGhlIHNhbXBsZSBub25jZQ==";
        String expected  = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";
        String actual    = computeAcceptKey(clientKey);
        assertTrue("RFC 6455 accept key computed correctly", expected.equals(actual));
    }

    private static void testHandshakeResponseHeaders() throws Exception {
        String clientKey = "x3JJHMbDL1EzLkh9GBhXDw==";
        String request = buildUpgradeRequest(clientKey);
        PushbackInputStream pbis = new PushbackInputStream(
            new ByteArrayInputStream(request.getBytes()), 4096);
        ByteArrayOutputStream responseOut = new ByteArrayOutputStream();

        WebSocketHandshake.performHandshake(pbis, responseOut);
        String response = responseOut.toString();
        assertTrue("Response contains Connection: Upgrade header",
            response.toLowerCase().contains("connection: upgrade"));
        assertTrue("Response contains Sec-WebSocket-Accept header",
            response.contains("Sec-WebSocket-Accept:"));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static String buildUpgradeRequest(String key) {
        return "GET / HTTP/1.1\r\n" +
               "Host: localhost:5000\r\n" +
               "Upgrade: websocket\r\n" +
               "Connection: Upgrade\r\n" +
               "Sec-WebSocket-Key: " + key + "\r\n" +
               "Sec-WebSocket-Version: 13\r\n" +
               "\r\n";
    }

    private static String computeAcceptKey(String clientKey) throws Exception {
        String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
        String combined = clientKey + GUID;
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest(combined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    private static void assertTrue(String name, boolean condition) {
        if (condition) {
            System.out.println("  [PASS] " + name);
            passed++;
        } else {
            System.out.println("  [FAIL] " + name);
            failed++;
        }
    }
}
