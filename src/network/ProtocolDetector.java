package network;

import java.io.IOException;
import java.io.PushbackInputStream;
import java.net.Socket;

/**
 * Detects whether an incoming socket connection is using the raw TCP text protocol
 * or a WebSocket handshake upgrade request.
 *
 * <p>Detection works by peeking at the first 4 bytes. A brief read timeout (500 ms)
 * is applied during the peek so that plain TCP clients that connect silently — i.e.
 * they wait for the server to speak first — are not blocked forever. After detection
 * the original socket timeout is restored.
 */
public final class ProtocolDetector {

    /** How long to wait for the initial peek bytes before assuming TCP_TEXT. */
    private static final int PEEK_TIMEOUT_MS = 500;

    /** Supported protocol modes. */
    public enum Protocol {
        /** Raw line-based TCP text. */
        TCP_TEXT,
        /** RFC 6455 WebSocket framing. */
        WEBSOCKET
    }

    private ProtocolDetector() {}

    /**
     * Peeks at the first 4 bytes of the stream without consuming them.
     *
     * <p>A short read timeout is applied so that silent TCP clients (those that
     * wait for a server greeting before sending anything) are not blocked. If no
     * data arrives within {@value #PEEK_TIMEOUT_MS} ms, {@link Protocol#TCP_TEXT}
     * is returned and the stream is left unmodified.
     *
     * @param socket the accepted client socket (used to set/restore SO_TIMEOUT)
     * @param pbis   pushback input stream wrapping the socket stream
     * @return the detected protocol
     * @throws IOException on unrecoverable stream errors
     */
    public static Protocol detect(Socket socket, PushbackInputStream pbis) throws IOException {
        int originalTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(PEEK_TIMEOUT_MS);

            byte[] peek = new byte[4];
            int readBytes;
            try {
                readBytes = pbis.read(peek, 0, 4);
            } catch (java.net.SocketTimeoutException e) {
                // No bytes arrived in time — assume a plain TCP client waiting for greeting
                return Protocol.TCP_TEXT;
            }

            if (readBytes <= 0) {
                return Protocol.TCP_TEXT;
            }

            // Push back whatever was read so subsequent handlers see a full stream
            pbis.unread(peek, 0, readBytes);

            if (readBytes >= 4 &&
                peek[0] == 'G' &&
                peek[1] == 'E' &&
                peek[2] == 'T' &&
                peek[3] == ' ') {
                return Protocol.WEBSOCKET;
            }

            return Protocol.TCP_TEXT;

        } finally {
            // Always restore the original timeout
            socket.setSoTimeout(originalTimeout);
        }
    }
}
