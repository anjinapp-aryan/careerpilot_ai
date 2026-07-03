package ai.careerpilot.jobdiscovery;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Deterministic (AI-free) domain taxonomy shared by the enricher and the scorer so the two can
 * never disagree on what a skill/role/industry "is". Four concerns, all pure functions:
 *
 * <ul>
 *   <li><b>Skill families</b> — collapse surface variants ("springboot"/"spring boot"/"spring",
 *       "micro services"/"microservices", "k8s"/"kubernetes") to one canonical family, so skill
 *       matching is not defeated by spelling.</li>
 *   <li><b>Role taxonomy</b> — map a title/role string to coarse role <i>families</i>
 *       (BACKEND/FRONTEND/ARCHITECT/LEAD/…), so "Java Architect" and "Backend Engineer" are seen
 *       as related instead of scoring 0 on raw token overlap.</li>
 *   <li><b>Seniority</b> — extract a seniority level from a title so a 12-yr candidate can be
 *       matched up (Senior/Lead/Principal/Architect) and down-ranked on junior/intern roles.</li>
 *   <li><b>Industry / job family</b> — classify a job as TECH vs MARKETING/SALES/HR/… so the
 *       recommendation quality filter can exclude non-technical roles by default.</li>
 * </ul>
 */
@Component
public class JobTaxonomy {

    // ── Industry / job family ────────────────────────────────────────────────────────

    public static final String FAMILY_TECH = "TECH";
    public static final String FAMILY_OTHER = "OTHER";

    /** Non-technical families excluded from a tech candidate's recommendations by default. */
    public static final Set<String> EXCLUDED_FAMILIES =
            Set.of("MARKETING", "SALES", "HR", "RECRUITER", "SUPPORT", "FINANCE");

    /** Title tokens that mark a role as fundamentally technical (wins over "sales"/"marketing"). */
    private static final List<String> TECH_ROLE_TOKENS = List.of(
            "software engineer", "engineer", "developer", "programmer", "architect", "sre",
            "devops", "data scientist", "data engineer", "machine learning", "ml engineer",
            "full stack", "fullstack", "back end", "backend", "front end", "frontend",
            "sysadmin", "system administrator", "qa engineer", "test engineer", "sdet",
            "platform engineer", "cloud engineer", "security engineer", "mobile developer",
            "android", "ios developer", "web developer", "technical lead", "tech lead");

    /** Non-tech family keyword → family, checked against the TITLE only (the strongest signal). */
    private static final Map<String, String> FAMILY_KEYWORDS = buildFamilyKeywords();

    /**
     * Classify a job's family from its title (primary) and description (tiebreak). A technical role
     * noun in the title always wins — so "Sales Engineer" / "Marketing Engineer" classify as TECH
     * (they are technical roles), while "Sales Executive" / "Marketing Manager" do not.
     */
    public String classifyFamily(String title, String description) {
        String t = title == null ? "" : title.toLowerCase();
        if (containsAny(t, TECH_ROLE_TOKENS)) return FAMILY_TECH;

        for (Map.Entry<String, String> e : FAMILY_KEYWORDS.entrySet()) {
            if (t.contains(e.getKey())) return e.getValue();
        }

        // No role noun in the title — fall back to tech density in the body.
        String body = (t + " " + (description == null ? "" : description.toLowerCase()));
        return techDensity(body) >= 2 ? FAMILY_TECH : FAMILY_OTHER;
    }

    /** True when a tech candidate should not see this family in Recommended. NULL/OTHER are kept. */
    public boolean isExcludedFamily(String family) {
        return family != null && EXCLUDED_FAMILIES.contains(family);
    }

    private int techDensity(String haystack) {
        int n = 0;
        for (String s : JobScoring.SKILL_VOCABULARY) if (haystack.contains(s)) { if (++n >= 2) break; }
        return n;
    }

    // ── Skill families ───────────────────────────────────────────────────────────────

    /** surface variant → canonical family. Lower-case keys; longest/most-specific handled first. */
    private static final Map<String, String> SKILL_FAMILY = buildSkillFamily();

    /** Canonical family for one skill token, or the trimmed lower-cased token itself if unknown. */
    public String skillFamily(String skill) {
        if (skill == null) return "";
        String s = skill.toLowerCase().trim();
        return SKILL_FAMILY.getOrDefault(s, s);
    }

    /** Distinct skill families present in a collection of raw skill strings. */
    public Set<String> skillFamilies(Collection<String> skills) {
        if (skills == null) return Set.of();
        Set<String> out = new HashSet<>();
        for (String s : skills) {
            if (s == null || s.isBlank()) continue;
            out.add(skillFamily(s));
        }
        out.remove("");
        return out;
    }

    /** Skill families detected by scanning free text for known surface forms. */
    public Set<String> skillFamiliesInText(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String h = text.toLowerCase();
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, String> e : SKILL_FAMILY.entrySet()) {
            if (containsToken(h, e.getKey())) out.add(e.getValue());
        }
        // Vocabulary terms that are their own family (no variant mapping).
        for (String v : JobScoring.SKILL_VOCABULARY) {
            if (!SKILL_FAMILY.containsKey(v) && containsToken(h, v)) out.add(v);
        }
        return out;
    }

    // ── Role taxonomy ────────────────────────────────────────────────────────────────

    /** role family → its trigger phrases (checked as substrings of a lower-cased title/role). */
    private static final Map<String, List<String>> ROLE_FAMILY = buildRoleFamily();

    /** Coarse role families implied by a title/role string (e.g. "Java Architect" → ARCHITECT, BACKEND). */
    public Set<String> roleFamilies(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String h = text.toLowerCase();
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, List<String>> e : ROLE_FAMILY.entrySet()) {
            if (containsAny(h, e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    // ── Seniority ────────────────────────────────────────────────────────────────────

    public static final int SENIORITY_UNKNOWN = -1;
    public static final int SENIORITY_INTERN = 0;
    public static final int SENIORITY_JUNIOR = 1;
    public static final int SENIORITY_MID = 2;
    public static final int SENIORITY_SENIOR = 3;
    public static final int SENIORITY_LEAD = 4;
    public static final int SENIORITY_PRINCIPAL = 5;

    /** Seniority level from a title, or {@link #SENIORITY_UNKNOWN} when the title carries no signal. */
    public int seniorityLevel(String title) {
        if (title == null) return SENIORITY_UNKNOWN;
        String t = title.toLowerCase();
        if (containsAny(t, List.of("intern", "internship", "trainee", "graduate", "apprentice"))) return SENIORITY_INTERN;
        if (containsAny(t, List.of("principal", "staff", "distinguished", "fellow", "head of", "director", "vp ", "chief"))) return SENIORITY_PRINCIPAL;
        if (containsAny(t, List.of("lead", "manager", "architect"))) return SENIORITY_LEAD;
        if (containsAny(t, List.of("senior", "sr.", "sr ", "principal"))) return SENIORITY_SENIOR;
        if (containsAny(t, List.of("junior", "jr.", "jr ", "associate", "entry level", "entry-level"))) return SENIORITY_JUNIOR;
        if (containsAny(t, List.of("mid level", "mid-level"))) return SENIORITY_MID;
        return SENIORITY_UNKNOWN;
    }

    /** Map years of experience to the seniority bar a candidate is positioned at. */
    public int seniorityFromYears(Integer years) {
        if (years == null) return SENIORITY_UNKNOWN;
        if (years <= 0) return SENIORITY_INTERN;
        if (years <= 2) return SENIORITY_JUNIOR;
        if (years <= 5) return SENIORITY_MID;
        if (years <= 9) return SENIORITY_SENIOR;
        return SENIORITY_LEAD; // 10+ years → lead/principal territory
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String n : needles) if (haystack.contains(n)) return true;
        return false;
    }

    /** Word-ish boundary match so "go" doesn't fire inside "google", "react" matches "reactjs". */
    private static boolean containsToken(String haystack, String token) {
        int idx = haystack.indexOf(token);
        while (idx >= 0) {
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            int end = idx + token.length();
            // allow trailing "js"/"." variants (react → reactjs, node → node.js) by only requiring a
            // left boundary for multi-char alpha tokens; short tokens require both boundaries.
            boolean rightOk = end >= haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end))
                    || token.length() >= 4;
            if (leftOk && rightOk) return true;
            idx = haystack.indexOf(token, idx + 1);
        }
        return false;
    }

    private static Map<String, String> buildSkillFamily() {
        Map<String, String> m = new LinkedHashMap<>();
        // Spring ecosystem
        for (String s : List.of("spring boot", "springboot", "spring", "spring cloud", "spring mvc")) m.put(s, "spring");
        // Microservices
        for (String s : List.of("microservices", "micro services", "micro-services")) m.put(s, "microservices");
        // Kubernetes
        for (String s : List.of("kubernetes", "k8s")) m.put(s, "kubernetes");
        // React
        for (String s : List.of("react", "reactjs", "react.js", "react js")) m.put(s, "react");
        // Node
        for (String s : List.of("node", "node.js", "nodejs", "node js")) m.put(s, "node");
        // AWS
        for (String s : List.of("aws", "amazon web services")) m.put(s, "aws");
        // GCP
        for (String s : List.of("gcp", "google cloud", "google cloud platform")) m.put(s, "gcp");
        // Azure
        for (String s : List.of("azure", "microsoft azure")) m.put(s, "azure");
        // Postgres
        for (String s : List.of("postgresql", "postgres", "psql")) m.put(s, "postgresql");
        // CI/CD
        for (String s : List.of("ci/cd", "cicd", "ci cd")) m.put(s, "ci/cd");
        // JS/TS
        m.put("javascript", "javascript");
        m.put("js", "javascript");
        m.put("typescript", "typescript");
        m.put("ts", "typescript");
        // Go
        for (String s : List.of("golang", "go lang")) m.put(s, "go");
        // Spark / data
        m.put("apache spark", "spark");
        m.put("spark", "spark");
        // Terraform / IaC
        m.put("terraform", "terraform");
        m.put("iac", "terraform");
        return m;
    }

    private static Map<String, List<String>> buildRoleFamily() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("ARCHITECT", List.of("architect", "architecture"));
        m.put("LEAD", List.of("lead", "principal", "staff", "manager", "head of", "director"));
        m.put("BACKEND", List.of("backend", "back end", "back-end", "java", "software engineer",
                "software developer", "server", "api ", "spring", "microservice"));
        m.put("FRONTEND", List.of("frontend", "front end", "front-end", "react", "angular", "vue", "ui engineer"));
        m.put("FULLSTACK", List.of("full stack", "fullstack", "full-stack"));
        m.put("DATA", List.of("data engineer", "data scientist", "data analyst", "spark", "etl", "analytics"));
        m.put("DEVOPS", List.of("devops", "sre", "site reliability", "platform engineer", "infrastructure", "cloud engineer"));
        m.put("MOBILE", List.of("android", "ios", "mobile developer", "react native", "flutter"));
        m.put("ML", List.of("machine learning", "ml engineer", "deep learning", "ai engineer", "mlops"));
        m.put("QA", List.of("qa engineer", "test engineer", "sdet", "quality assurance", "automation test"));
        m.put("SECURITY", List.of("security engineer", "appsec", "infosec", "cybersecurity"));
        return m;
    }

    private static Map<String, String> buildFamilyKeywords() {
        // Insertion order matters: more specific families (RECRUITER) before broader (HR).
        Map<String, String> m = new LinkedHashMap<>();
        m.put("recruiter", "RECRUITER");
        m.put("talent acquisition", "RECRUITER");
        m.put("sourcer", "RECRUITER");
        m.put("human resources", "HR");
        m.put("people operations", "HR");
        m.put("hr ", "HR");
        m.put("hr manager", "HR");
        m.put("hr business partner", "HR");
        m.put("marketing", "MARKETING");
        m.put("seo specialist", "MARKETING");
        m.put("content writer", "MARKETING");
        m.put("copywriter", "MARKETING");
        m.put("social media", "MARKETING");
        m.put("brand manager", "MARKETING");
        m.put("growth manager", "MARKETING");
        m.put("sales", "SALES");
        m.put("account executive", "SALES");
        m.put("business development", "SALES");
        m.put("account manager", "SALES");
        m.put("customer support", "SUPPORT");
        m.put("customer service", "SUPPORT");
        m.put("customer success", "SUPPORT");
        m.put("support specialist", "SUPPORT");
        m.put("help desk", "SUPPORT");
        m.put("helpdesk", "SUPPORT");
        m.put("accountant", "FINANCE");
        m.put("financial analyst", "FINANCE");
        m.put("bookkeeper", "FINANCE");
        m.put("payroll", "FINANCE");
        m.put("auditor", "FINANCE");
        m.put("finance manager", "FINANCE");
        return m;
    }
}
