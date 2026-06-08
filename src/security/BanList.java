package security;

import core.Logger;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory and file-persisted ban list for AegisCore.
 *
 * <p>Bans are checked before accepting connections (IP ban) and before the
 * {@code NAME} command completes (name ban). Expired entries are lazily removed
 * on each check. All mutations persist to {@code logs/banlist.txt} immediately.
 *
 * <p>Singleton. Thread-safe via {@link CopyOnWriteArrayList}.
 */
public final class BanList {

    private static final BanList INSTANCE = new BanList();
    private static final String  BAN_FILE  = "logs/banlist.txt";

    private final CopyOnWriteArrayList<BanEntry> entries = new CopyOnWriteArrayList<>();

    private BanList() {
        new File("logs").mkdirs();
        loadFromFile();
    }

    /** Returns the singleton {@code BanList}. */
    public static BanList getInstance() {
        return INSTANCE;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Bans an IP address.
     *
     * @param ip       the IP to ban
     * @param reason   reason for the ban
     * @param duration ban duration, or {@code null} for permanent
     */
    public void banIp(String ip, String reason, Duration duration) {
        Instant expiry = duration == null ? null : Instant.now().plus(duration);
        add(new BanEntry(ip, BanTarget.IP, reason, expiry));
    }

    /**
     * Bans a player display name.
     *
     * @param name     the display name to ban
     * @param reason   reason for the ban
     * @param duration ban duration, or {@code null} for permanent
     */
    public void banName(String name, String reason, Duration duration) {
        Instant expiry = duration == null ? null : Instant.now().plus(duration);
        add(new BanEntry(name.toLowerCase(), BanTarget.NAME, reason, expiry));
    }

    /**
     * Returns {@code true} if the given IP is currently banned (and not expired).
     *
     * @param ip the IP address to check
     * @return whether the IP is banned
     */
    public boolean isIpBanned(String ip) {
        return isBanned(ip, BanTarget.IP);
    }

    /**
     * Returns {@code true} if the given display name is currently banned (and not expired).
     *
     * @param name the display name to check (case-insensitive)
     * @return whether the name is banned
     */
    public boolean isNameBanned(String name) {
        return isBanned(name.toLowerCase(), BanTarget.NAME);
    }

    /**
     * Removes all ban entries matching the given target string (IP or name).
     *
     * @param target the target to unban
     */
    public void unban(String target) {
        entries.removeIf(e -> e.target().equalsIgnoreCase(target));
        persist();
    }

    /**
     * Returns an unmodifiable snapshot of all current ban entries (including expired ones
     * not yet evicted).
     *
     * @return list of all bans
     */
    public List<BanEntry> getAllBans() {
        return Collections.unmodifiableList(entries);
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private boolean isBanned(String target, BanTarget type) {
        entries.removeIf(BanEntry::isExpired);
        for (BanEntry e : entries) {
            if (e.type() == type && e.target().equalsIgnoreCase(target) && !e.isExpired()) {
                return true;
            }
        }
        return false;
    }

    private void add(BanEntry entry) {
        entries.add(entry);
        persist();
        Logger.logRegistry("Ban added: " + entry.summary());
    }

    private synchronized void persist() {
        try (PrintWriter w = new PrintWriter(new FileWriter(BAN_FILE, false))) {
            for (BanEntry e : entries) {
                if (!e.isExpired()) {
                    String expiry = e.expiry() == null ? "PERMANENT" : e.expiry().toString();
                    w.println(e.type() + "," + e.target() + "," + e.reason() + "," + expiry);
                }
            }
        } catch (IOException ex) {
            Logger.logServerError("BanList: failed to persist: " + ex.getMessage());
        }
    }

    private void loadFromFile() {
        File f = new File(BAN_FILE);
        if (!f.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split(",", 4);
                if (parts.length < 4) continue;
                BanTarget type   = BanTarget.valueOf(parts[0].trim());
                String    target = parts[1].trim();
                String    reason = parts[2].trim();
                Instant   expiry = "PERMANENT".equals(parts[3].trim())
                                    ? null : Instant.parse(parts[3].trim());
                entries.add(new BanEntry(target, type, reason, expiry));
            }
        } catch (Exception ex) {
            Logger.logServerError("BanList: failed to load: " + ex.getMessage());
        }
    }
}
