package admin;

import core.Logger;

import java.io.*;
import java.time.Instant;

/**
 * Dedicated audit log for all administrative actions taken on AegisCore.
 *
 * <p>Every kick, ban, unban, room closure, or configuration change made by
 * an admin session is recorded here with a timestamp and the admin's identity.
 * Writes to {@code logs/admin_audit.log}.
 *
 * <p>Singleton. Thread-safe: all write operations are synchronized on {@code this}.
 */
public final class AdminAuditLog {

    private static final AdminAuditLog INSTANCE  = new AdminAuditLog();
    private static final String        AUDIT_FILE = "logs/admin_audit.log";

    private AdminAuditLog() {
        new File("logs").mkdirs();
    }

    /** Returns the singleton {@code AdminAuditLog}. */
    public static AdminAuditLog getInstance() {
        return INSTANCE;
    }

    /**
     * Records an admin action.
     *
     * @param adminName human-readable admin identifier
     * @param action    the action taken (e.g., {@code "KICK"}, {@code "BAN"})
     * @param target    who/what was acted upon (player name, IP, room ID)
     * @param detail    additional context or reason
     */
    public synchronized void log(String adminName, String action, String target, String detail) {
        String entry = String.format("[%s] ADMIN:%-12s ACTION:%-10s TARGET:%-20s DETAIL:%s",
            Instant.now(), adminName, action, target, detail);
        System.out.println(entry);
        try (PrintWriter w = new PrintWriter(new FileWriter(AUDIT_FILE, true))) {
            w.println(entry);
        } catch (IOException ex) {
            Logger.logServerError("AdminAuditLog: write failed: " + ex.getMessage());
        }
    }
}
