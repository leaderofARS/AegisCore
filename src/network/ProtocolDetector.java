package network;

import java.io.IOException;
import java.io.PushbackInputStream;

/**
 * Detects whether an incoming socket connection is using the raw TCP text protocol
 * or a WebSocket handshake upgrade request.
 */
public final class ProtocolDetector {

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
     * @param pbis pushback input stream wrapping the socket stream
     * @return the detected protocol
     * @throws IOException on stream read errors
     */
    public static Protocol detect(PushbackInputStream pbis) throws IOException {
        byte[] peek = new byte[4];
        int readBytes = pbis.read(peek, 0, 4);
        if (readBytes <= 0) {
            return Protocol.TCP_TEXT;
        }

        // Push back whatever was read so that subsequent handlers read from the beginning
        pbis.unread(peek, 0, readBytes);

        if (readBytes >= 4 &&
            peek[0] == 'G' &&
            peek[1] == 'E' &&
            peek[2] == 'T' &&
            peek[3] == ' ') {
            return Protocol.WEBSOCKET;
        }

        return Protocol.TCP_TEXT;
    }
}
