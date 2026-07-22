package ai.careerpilot.learning.career.goal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 7.19.1 — a finer IC/management ladder than {@code JobTaxonomy.seniorityLevel()} provides.
 * {@code JobTaxonomy}'s 6 buckets deliberately collapse Tech Lead/Architect/Manager into one
 * "LEAD" bucket and Staff/Principal/Director into one "PRINCIPAL" bucket — correct for its job-
 * matching purpose, too coarse for a Career Goal Planner that needs to distinguish those tracks.
 * This is a NEW, separate, pure/deterministic classifier — it does not modify or replace {@code
 * JobTaxonomy}, so job-matching scoring is completely unaffected (zero regression risk).
 */
public final class CareerLevelTaxonomy {

    public static final String JUNIOR = "JUNIOR";
    public static final String MID = "MID";
    public static final String SENIOR = "SENIOR";
    public static final String TECH_LEAD = "TECH_LEAD";
    public static final String STAFF = "STAFF";
    public static final String PRINCIPAL = "PRINCIPAL";
    public static final String ARCHITECT = "ARCHITECT";
    public static final String ENGINEERING_MANAGER = "ENGINEERING_MANAGER";
    /** No confident keyword match — never guessed. */
    public static final String UNKNOWN = "UNKNOWN";

    private static final Map<Integer, List<String>> RANK = Map.of(
            1, List.of(JUNIOR), 2, List.of(MID), 3, List.of(SENIOR),
            4, List.of(TECH_LEAD, ARCHITECT), 5, List.of(STAFF, ENGINEERING_MANAGER), 6, List.of(PRINCIPAL));

    /** The spec's named goals, mapped to a level on this ladder. Order matters for display. */
    private static final Map<String, String> SUPPORTED_GOALS = buildGoals();

    private CareerLevelTaxonomy() {}

    /** Deterministic keyword classification of a job/role title. Never fabricates a level. */
    public static String classify(String title) {
        if (title == null || title.isBlank()) return UNKNOWN;
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("engineering manager") || t.contains("eng manager") || t.contains("em ")) return ENGINEERING_MANAGER;
        if (t.contains("principal")) return PRINCIPAL;
        if (t.contains("staff")) return STAFF;
        if (t.contains("architect")) return ARCHITECT;
        if (t.contains("tech lead") || t.contains("technical lead") || t.contains("team lead")) return TECH_LEAD;
        if (t.contains("senior") || t.contains("sr.") || t.contains(" sr ")) return SENIOR;
        if (t.contains("junior") || t.contains("jr.") || t.contains("intern") || t.contains("graduate")) return JUNIOR;
        if (t.contains("mid") || t.contains("engineer") || t.contains("developer")) return MID;
        return UNKNOWN;
    }

    /** Ordinal rank (1=lowest) for gap-size math. -1 for UNKNOWN — never treated as a real distance. */
    public static int rank(String level) {
        if (level == null) return -1;
        return RANK.entrySet().stream().filter(e -> e.getValue().contains(level))
                .map(Map.Entry::getKey).findFirst().orElse(-1);
    }

    public static List<String> supportedGoals() {
        return List.copyOf(SUPPORTED_GOALS.keySet());
    }

    /** Level for a named goal (e.g. "Staff Engineer" -> STAFF), or UNKNOWN if not a recognized goal. */
    public static String levelForGoal(String goalName) {
        if (goalName == null) return UNKNOWN;
        return SUPPORTED_GOALS.getOrDefault(goalName.toLowerCase(Locale.ROOT).trim(), UNKNOWN);
    }

    private static Map<String, String> buildGoals() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("senior java engineer", SENIOR);
        m.put("senior engineer", SENIOR);
        m.put("tech lead", TECH_LEAD);
        m.put("software architect", ARCHITECT);
        m.put("principal engineer", PRINCIPAL);
        m.put("staff engineer", STAFF);
        m.put("engineering manager", ENGINEERING_MANAGER);
        m.put("cloud architect", ARCHITECT);
        m.put("ai engineer", MID);
        m.put("platform engineer", MID);
        m.put("solutions architect", ARCHITECT);
        return m;
    }
}
