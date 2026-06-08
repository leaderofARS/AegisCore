package network;

import core.Logger;
import player.PlayerRegistry;
import protocol.CommandRouter;
import server.ClientHandler;

import java.net.Socket;

/**
 * WebSocket-aware extension of {@link ClientHandler}.
 *
 * <p>This subclass forces the protocol detection to skip straight to the
 * WebSocket handshake path, bypassing the TCP text protocol entirely. It is
 * used by server deployments that expose a dedicated WebSocket port (e.g., 5001)
 * for browser-based Unity/WebGL clients.
 *
 * <p>All other behaviour — heartbeat, rate limiting, command routing — is
 * inherited unchanged from the base {@link ClientHandler}.
 *
 * <h3>Usage</h3>
 * <pre>
 *   WebSocketClientHandler wsh = new WebSocketClientHandler(socket, registry, router);
 *   ClientThreadPool.getInstance().submit(wsh, "WSPlayer-" + socket.getPort());
 * </pre>
 *
 * <h3>Integration</h3>
 * Instantiate from the WebSocket accept-loop in {@code Server.main()} when a
 * {@link ProtocolDetector} identifies an incoming connection as WebSocket.
 * For mixed-protocol deployments, {@link ClientHandler} itself handles both
 * TCP and WebSocket via {@link ProtocolDetector}, making this class optional.
 */
public final class WebSocketClientHandler extends ClientHandler {

    /**
     * Constructs a WebSocket-only client handler.
     *
     * @param socket         accepted WebSocket client socket
     * @param playerRegistry shared player registry
     * @param commandRouter  shared command router
     */
    public WebSocketClientHandler(Socket socket, PlayerRegistry playerRegistry,
                                  CommandRouter commandRouter) {
        super(socket, playerRegistry, commandRouter);
    }

    /**
     * Logs the dedicated WebSocket connection before delegating to the standard run loop.
     * The base class {@link ClientHandler#run()} will call {@link ProtocolDetector} and
     * detect the WebSocket handshake automatically.
     */
    @Override
    public void run() {
        Logger.logServer("[WebSocket] Accepted WebSocket client: " + getSessionId());
        super.run();
    }
}
