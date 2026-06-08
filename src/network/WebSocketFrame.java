package network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Utility class for encoding and decoding WebSocket data frames (RFC 6455).
 *
 * <p>Handles opcodes (text and close), payload lengths, masking/unmasking, and close frames.
 */
public final class WebSocketFrame {

    private WebSocketFrame() {}

    /**
     * Encodes a plain text message into an unmasked WebSocket text frame.
     *
     * @param text the message payload
     * @return the framed byte array
     */
    public static byte[] encode(String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        int len = payload.length;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(0x81); // FIN = 1, Opcode = 1 (text)
        if (len <= 125) {
            baos.write(len);
        } else if (len <= 65535) {
            baos.write(126);
            baos.write((len >> 8) & 0xFF);
            baos.write(len & 0xFF);
        } else {
            baos.write(127);
            baos.write(0); baos.write(0); baos.write(0); baos.write(0);
            baos.write((len >> 24) & 0xFF);
            baos.write((len >> 16) & 0xFF);
            baos.write((len >> 8) & 0xFF);
            baos.write(len & 0xFF);
        }
        try {
            baos.write(payload);
        } catch (IOException e) {
            // Will not happen with ByteArrayOutputStream
        }
        return baos.toByteArray();
    }

    /**
     * Decodes a single WebSocket frame from the input stream.
     *
     * @param is the socket input stream
     * @return the decoded text payload, or null if it's a close frame or EOF is reached
     * @throws IOException on read or frame errors
     */
    public static String decode(InputStream is) throws IOException {
        int b0 = is.read();
        if (b0 == -1) return null;
        int fin = (b0 >> 7) & 1;
        int opcode = b0 & 0x0F;

        int b1 = is.read();
        if (b1 == -1) return null;
        boolean masked = ((b1 >> 7) & 1) == 1;
        long payloadLen = b1 & 0x7F;

        if (payloadLen == 126) {
            int len1 = is.read();
            int len2 = is.read();
            if (len1 == -1 || len2 == -1) return null;
            payloadLen = (len1 << 8) | len2;
        } else if (payloadLen == 127) {
            long len = 0;
            for (int i = 0; i < 8; i++) {
                int val = is.read();
                if (val == -1) return null;
                len = (len << 8) | val;
            }
            payloadLen = len;
        }

        // Close frame
        if (opcode == 0x08) {
            return null;
        }

        byte[] maskKey = null;
        if (masked) {
            maskKey = new byte[4];
            int totalRead = 0;
            while (totalRead < 4) {
                int read = is.read(maskKey, totalRead, 4 - totalRead);
                if (read == -1) return null;
                totalRead += read;
            }
        }

        byte[] payload = new byte[(int) payloadLen];
        int bytesRead = 0;
        while (bytesRead < payloadLen) {
            int read = is.read(payload, bytesRead, (int) (payloadLen - bytesRead));
            if (read == -1) return null;
            bytesRead += read;
        }

        if (masked && maskKey != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ maskKey[i % 4]);
            }
        }

        return new String(payload, StandardCharsets.UTF_8);
    }

    /**
     * Creates an unmasked close frame.
     *
     * @return framed close frame bytes
     */
    public static byte[] encodeClose() {
        return new byte[] { (byte) 0x88, 0x00 };
    }
}
