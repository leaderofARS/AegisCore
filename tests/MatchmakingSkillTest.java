import matchmaking.MatchConfig;
import matchmaking.MatchmakingPolicy;
import matchmaking.PlayerSkillProfile;
import matchmaking.RegionTag;
import matchmaking.SkillBracket;
import player.Player;
import server.ClientHandler;

import java.net.Socket;

/**
 * Tests for the skill-bracket matchmaking components.
 *
 * <p>Validates that:
 * <ul>
 *   <li>Players in the same MMR range are compatible under {@link MatchmakingPolicy#SKILL_BASED}.</li>
 *   <li>Players with MMR deltas beyond {@code maxSkillDelta} are rejected.</li>
 *   <li>Regional compatibility logic works correctly.</li>
 *   <li>{@link SkillBracket#forRating(int)} classifies correctly.</li>
 *   <li>Match history prevents re-matching.</li>
 * </ul>
 *
 * <p>Run directly (no external test framework):
 * <pre>
 *   javac -cp . -sourcepath src tests/MatchmakingSkillTest.java
 *   java -cp . MatchmakingSkillTest
 * </pre>
 */
public class MatchmakingSkillTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== MatchmakingSkillTest ===\n");

        testSkillBracketClassification();
        testSkillBracketContains();
        testSkillDeltaCompatible();
        testSkillDeltaIncompatible();
        testRegionalCompatibility();
        testMatchHistoryPreventsRematch();
        testPlayerSkillProfileDefault();

        System.out.println("\n--- Results ---");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        if (failed > 0) { System.exit(1); }
    }

    // -----------------------------------------------------------------------

    private static void testSkillBracketClassification() {
        assertTrue("MMR 0 → Bronze",    SkillBracket.forRating(0)    == SkillBracket.BRONZE);
        assertTrue("MMR 999 → Bronze",  SkillBracket.forRating(999)  == SkillBracket.BRONZE);
        assertTrue("MMR 1000 → Silver", SkillBracket.forRating(1000) == SkillBracket.SILVER);
        assertTrue("MMR 1500 → Gold",   SkillBracket.forRating(1500) == SkillBracket.GOLD);
        assertTrue("MMR 2000 → Plat",   SkillBracket.forRating(2000) == SkillBracket.PLATINUM);
        assertTrue("MMR 2500 → Diam",   SkillBracket.forRating(2500) == SkillBracket.DIAMOND);
        assertTrue("MMR 9999 → Diam",   SkillBracket.forRating(9999) == SkillBracket.DIAMOND);
    }

    private static void testSkillBracketContains() {
        assertTrue("Bronze contains 500",      SkillBracket.BRONZE.contains(500));
        assertTrue("Bronze does not contain 1000", !SkillBracket.BRONZE.contains(1000));
        assertTrue("Silver contains 1200",     SkillBracket.SILVER.contains(1200));
    }

    private static void testSkillDeltaCompatible() {
        String sid1 = "skill-p1", sid2 = "skill-p2";
        PlayerSkillProfile.getOrCreate(sid1).adjustRating(200  - PlayerSkillProfile.DEFAULT_RATING);
        PlayerSkillProfile.getOrCreate(sid2).adjustRating(400  - PlayerSkillProfile.DEFAULT_RATING);
        // delta = 200, maxSkillDelta = 300 → compatible
        MatchConfig config = new MatchConfig(2, 300, RegionTag.ANY, "standard", 30);
        Player p1 = makePlayer(sid1);
        Player p2 = makePlayer(sid2);
        java.util.List<Player> group = java.util.List.of(p1);
        assertTrue("Players within 300 MMR delta are compatible",
            MatchmakingPolicy.SKILL_BASED.isCompatible(p2, group, config));
    }

    private static void testSkillDeltaIncompatible() {
        String sid1 = "skill-inc-p1", sid2 = "skill-inc-p2";
        PlayerSkillProfile.getOrCreate(sid1).adjustRating(0    - PlayerSkillProfile.DEFAULT_RATING);
        PlayerSkillProfile.getOrCreate(sid2).adjustRating(800  - PlayerSkillProfile.DEFAULT_RATING);
        // delta = 800, maxSkillDelta = 300 → incompatible
        MatchConfig config = new MatchConfig(2, 300, RegionTag.ANY, "standard", 30);
        Player p1 = makePlayer(sid1);
        Player p2 = makePlayer(sid2);
        java.util.List<Player> group = java.util.List.of(p1);
        assertTrue("Players with delta > maxSkillDelta are incompatible",
            !MatchmakingPolicy.SKILL_BASED.isCompatible(p2, group, config));
    }

    private static void testRegionalCompatibility() {
        String sid1 = "reg-p1", sid2 = "reg-p2", sid3 = "reg-p3";
        PlayerSkillProfile pp1 = PlayerSkillProfile.getOrCreate(sid1);
        PlayerSkillProfile pp2 = PlayerSkillProfile.getOrCreate(sid2);
        PlayerSkillProfile pp3 = PlayerSkillProfile.getOrCreate(sid3);
        pp1.setRegion(RegionTag.EU_WEST);
        pp2.setRegion(RegionTag.EU_WEST);
        pp3.setRegion(RegionTag.ASIA_PACIFIC);

        MatchConfig config = new MatchConfig(2, Integer.MAX_VALUE, RegionTag.EU_WEST, "standard", 30);
        Player p1 = makePlayer(sid1);
        Player p2 = makePlayer(sid2);
        Player p3 = makePlayer(sid3);
        java.util.List<Player> group = java.util.List.of(p1);

        assertTrue("EU_WEST player compatible with EU_WEST queue",
            MatchmakingPolicy.REGIONAL.isCompatible(p2, group, config));
        assertTrue("ASIA_PACIFIC player incompatible with EU_WEST queue",
            !MatchmakingPolicy.REGIONAL.isCompatible(p3, group, config));
    }

    private static void testMatchHistoryPreventsRematch() {
        String sid1 = "hist-p1", sid2 = "hist-p2";
        PlayerSkillProfile pp1 = PlayerSkillProfile.getOrCreate(sid1);
        pp1.recordMatch(sid2);
        assertTrue("wasRecentlyMatchedWith returns true after recording",
            pp1.wasRecentlyMatchedWith(sid2));
        assertTrue("wasRecentlyMatchedWith returns false for unknown opponent",
            !pp1.wasRecentlyMatchedWith("nobody"));
    }

    private static void testPlayerSkillProfileDefault() {
        String sid = "default-profile";
        PlayerSkillProfile pp = PlayerSkillProfile.getOrCreate(sid);
        assertTrue("Default MMR is " + PlayerSkillProfile.DEFAULT_RATING,
            pp.getRating() == PlayerSkillProfile.DEFAULT_RATING);
        assertTrue("Default bracket is Silver (1200 is Silver range)",
            pp.getBracket() == SkillBracket.SILVER);
        assertTrue("Default region is ANY",
            pp.getRegion() == RegionTag.ANY);
    }

    // -----------------------------------------------------------------------

    /** Creates a minimal Player stub with the given session ID. */
    private static Player makePlayer(String sessionId) {
        // Use a null handler — tests don't send messages
        return new Player(sessionId, null);
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
