package admin;

import player.Player;
import player.PlayerRegistry;
import room.Room;
import room.RoomRegistry;
import security.BanList;

import java.time.Duration;
import java.util.Collection;

/**
 * Static implementations of all administrative server commands.
 *
 * <p>Methods are stateless with respect to {@code this}; all state lives in
 * the injected singletons. Every action is recorded in the {@link AdminAuditLog}.
 *
 * <p>Non-instantiable.
 */
public final class AdminCommands {

    private AdminCommands() {}

    /**
     * Kicks a player from the server by display name.
     *
     * @param targetName  the display name of the player to kick
     * @param reason      reason string sent to the kicked player
     * @param registry    the live player registry
     * @param auditLog    audit log to record the action
     * @param adminName   identity of the issuing admin
     * @return a status message to send back to the admin
     */
    public static String kickPlayer(String targetName, String reason,
                                    PlayerRegistry registry, AdminAuditLog auditLog,
                                    String adminName) {
        Player target = findByName(targetName, registry);
        if (target == null) return "[ADMIN] Player not found: " + targetName;
        target.send("[ADMIN] You have been kicked. Reason: " + reason);
        target.getHandler().forceDisconnect();
        registry.broadcastAll("[SERVER] " + targetName + " was removed by admin.");
        auditLog.log(adminName, "KICK", targetName, reason);
        return "[ADMIN] Kicked: " + targetName;
    }

    /**
     * Bans a player's display name and optionally kicks them if currently online.
     *
     * @param targetName display name to ban
     * @param reason     reason for the ban
     * @param duration   ban duration ({@code null} = permanent)
     * @param banList    the ban list to mutate
     * @param registry   player registry for live kick
     * @param auditLog   audit log
     * @param adminName  identity of the issuing admin
     * @return status message
     */
    public static String banPlayerName(String targetName, String reason, Duration duration,
                                       BanList banList, PlayerRegistry registry,
                                       AdminAuditLog auditLog, String adminName) {
        banList.banName(targetName, reason, duration);
        Player online = findByName(targetName, registry);
        if (online != null) {
            online.send("[ADMIN] You have been banned. Reason: " + reason);
            online.getHandler().forceDisconnect();
        }
        auditLog.log(adminName, "BAN_NAME", targetName, reason + (duration == null ? " [PERM]" : " [" + duration + "]"));
        return "[ADMIN] Banned name: " + targetName;
    }

    /**
     * Bans an IP address and kicks any connected player from that IP.
     *
     * @param ip        IP address to ban
     * @param reason    reason for the ban
     * @param duration  ban duration ({@code null} = permanent)
     * @param banList   the ban list to mutate
     * @param registry  player registry for live kicks
     * @param auditLog  audit log
     * @param adminName identity of the issuing admin
     * @return status message
     */
    public static String banIp(String ip, String reason, Duration duration,
                                BanList banList, PlayerRegistry registry,
                                AdminAuditLog auditLog, String adminName) {
        banList.banIp(ip, reason, duration);
        for (Player p : registry.getAllPlayers()) {
            String sid = p.getSessionId();
            // sessionId format: /ip:port
            if (sid.contains(ip)) {
                p.send("[ADMIN] Your IP has been banned. Reason: " + reason);
                p.getHandler().forceDisconnect();
            }
        }
        auditLog.log(adminName, "BAN_IP", ip, reason);
        return "[ADMIN] Banned IP: " + ip;
    }

    /**
     * Removes a ban entry by target string (IP or name).
     *
     * @param target    the target to unban
     * @param banList   the ban list to mutate
     * @param auditLog  audit log
     * @param adminName identity of the issuing admin
     * @return status message
     */
    public static String unban(String target, BanList banList,
                                AdminAuditLog auditLog, String adminName) {
        banList.unban(target);
        auditLog.log(adminName, "UNBAN", target, "");
        return "[ADMIN] Removed ban for: " + target;
    }

    /**
     * Returns a formatted list of all currently online players.
     *
     * @param registry player registry
     * @return multi-line status string
     */
    public static String listPlayers(PlayerRegistry registry) {
        Collection<Player> players = registry.getAllPlayers();
        if (players.isEmpty()) return "[ADMIN] No players online.";
        StringBuilder sb = new StringBuilder("[ADMIN] Online players (" + players.size() + "):\n");
        for (Player p : players) {
            sb.append(String.format("  %-20s %-12s %s%n",
                p.getLabel(), p.getStatus(), p.getSessionId()));
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Returns a formatted list of all active rooms.
     *
     * @param registry room registry
     * @return multi-line status string
     */
    public static String listRooms(RoomRegistry registry) {
        var rooms = registry.getOpenRooms();
        if (rooms.isEmpty()) return "[ADMIN] No active rooms.";
        StringBuilder sb = new StringBuilder("[ADMIN] Active rooms (" + rooms.size() + "):\n");
        for (Room r : rooms) {
            sb.append(String.format("  %-8s %-20s %d/%d  %s%n",
                r.getRoomId(), r.getName(), r.getPlayerCount(), r.getMaxPlayers(), r.getState()));
        }
        return sb.toString().stripTrailing();
    }

    // -----------------------------------------------------------------------

    private static Player findByName(String displayName, PlayerRegistry registry) {
        for (Player p : registry.getAllPlayers()) {
            if (displayName.equalsIgnoreCase(p.getDisplayName())) return p;
        }
        return null;
    }
}
