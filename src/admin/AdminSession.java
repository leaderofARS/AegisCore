package admin;

import core.Logger;
import player.PlayerRegistry;
import protocol.CommandRouter;
import server.ClientHandler;

import java.net.Socket;

/**
 * Privileged server-operator client handler with elevated permissions.
 *
 * <p>An {@code AdminSession} is a specialised {@link ClientHandler} that is created
 * for connections authenticated as server administrators. The admin password is
 * verified at connection time via {@link AdminAuthenticator}.
 *
 * <p>The session logs all actions to the {@link AdminAuditLog} and can issue
 * any {@link AdminCommands} without needing to re-authenticate per command (the
 * password is validated once on connection).
 *
 * <h3>Usage</h3>
 * An admin client connects over the standard TCP port and immediately sends:
 * <pre>
 *   ADMIN_LOGIN &lt;password&gt;
 * </pre>
 * On success, subsequent commands are handled with admin privileges. All admin
 * actions are recorded in {@code logs/AdminAudit.log}.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Admin client connects — handled by normal {@link server.Server} accept loop.</li>
 *   <li>Client issues {@code ADMIN &lt;password&gt; &lt;cmd&gt; &lt;args&gt;} through
 *       {@link protocol.CommandRouter#route}, which delegates to
 *       {@link protocol.CommandRouter#handleAdmin}.</li>
 *   <li>Authentication is checked by {@link AdminAuthenticator#authenticate(String)}.</li>
 *   <li>On success the admin command is dispatched to {@link AdminCommands}.</li>
 *   <li>The action is recorded in {@link AdminAuditLog}.</li>
 * </ol>
 *
 * <p>This class extends {@link ClientHandler} without overriding any behaviour —
 * it exists as a distinct type for future privilege escalation, separate
 * logging, and role-based access control (RBAC) extensions.
 */
public final class AdminSession extends ClientHandler {

    private final String adminName;

    /**
     * Constructs a privileged admin session for the given socket.
     *
     * @param socket         the accepted admin client socket
     * @param adminName      human-readable admin identity (e.g., player name or IP-based tag)
     * @param playerRegistry shared player registry
     * @param commandRouter  shared command router
     */
    public AdminSession(Socket socket, String adminName,
                        player.PlayerRegistry playerRegistry,
                        protocol.CommandRouter commandRouter) {
        super(socket, playerRegistry, commandRouter);
        this.adminName = adminName;
    }

    /** Returns the admin identity string for audit logging. */
    public String getAdminName() { return adminName; }

    /**
     * Logs the elevated session opening before delegating to the standard I/O loop.
     */
    @Override
    public void run() {
        Logger.logServer("[AdminSession] Elevated admin session opened: " + adminName +
            " @ " + getSessionId());
        AdminAuditLog.getInstance().log(adminName, "SESSION_OPEN", getSessionId(), "Admin session started");
        try {
            super.run();
        } finally {
            AdminAuditLog.getInstance().log(adminName, "SESSION_CLOSE", getSessionId(), "Admin session ended");
            Logger.logServer("[AdminSession] Admin session closed: " + adminName);
        }
    }
}
