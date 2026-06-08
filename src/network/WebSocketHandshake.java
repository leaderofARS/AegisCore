package network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Handles the HTTP protocol upgrade handshake for WebSocket connections (RFC 6455).
 *
 * <p>Uses a byte-by-byte reader for headers to ensure no payload bytes are buffered or lost.
 */
public final class WebSocketHandshake {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketHandshake() {}

    /**
     * Inspects the upgrade request and performs a handshake if valid.
     *
     * @param is connection input stream
     * @param os connection output stream
     * @return true if upgrade handshake succeeded; false otherwise
     * @throws IOException on socket or hashing errors
     */
    public static boolean performHandshake(InputStream is, OutputStream os) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = is.read()) != -1) {
            sb.append((char) b);
            if (sb.length() >= 4 && sb.substring(sb.length() - 4).equals("\r\n\r\n")) {
                break;
            }
        }

        String headers = sb.toString();
        if (!headers.contains("GET")) {
            return false;
        }

        String key = null;
        String[] lines = headers.split("\r\n");
        for (String line : lines) {
            if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                key = line.substring(line.indexOf(":") + 1).trim();
                break;
            }
        }

        if (key == null) {
            return false;
        }

        String acceptKey;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hashed = md.digest((key + GUID).getBytes(StandardCharsets.UTF_8));
            acceptKey = Base64.getEncoder().encodeToString(hashed);
        } catch (Exception e) {
            throw new IOException("Failed to calculate WebSocket Accept key", e);
        }

        byte[] response = (
            "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n"
        ).getBytes(StandardCharsets.US_ASCII);
        os.write(response);
        os.flush();
        return true;
    }
}
