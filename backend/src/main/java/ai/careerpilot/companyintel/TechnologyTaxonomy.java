package ai.careerpilot.companyintel;

import ai.careerpilot.domain.CompanyRelationship;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 7.17.4 — Technology Intelligence. {@link CompanyRelationship} (TECHNOLOGY/SKILL edges) is a
 * flat bag of target strings today — no category column, no sub-classification anywhere. This class
 * adds a purely deterministic keyword→category lookup (no AI, no inference beyond a fixed table) so
 * a company's observed tech edges can be grouped into the taxonomy the spec asks for. Unmatched
 * terms classify as {@link #OTHER} rather than being guessed into a category.
 *
 * <p>Technology popularity is already the edge {@code weight} (reuse, not recomputed here).
 * Technology similarity/overlap already exists via {@code CompanySimilarityService} (reuse, not
 * duplicated). Growth/decline trends over time are NOT computed — {@code CompanyRelationship} only
 * stores a current weight, no historical time series of past weights exists to trend against.
 */
public final class TechnologyTaxonomy {

    public static final String LANGUAGE = "LANGUAGE";
    public static final String FRAMEWORK = "FRAMEWORK";
    public static final String CLOUD = "CLOUD";
    public static final String CONTAINER = "CONTAINER";
    public static final String CICD = "CICD";
    public static final String TESTING = "TESTING";
    public static final String SECURITY = "SECURITY";
    public static final String ARCHITECTURE = "ARCHITECTURE";
    public static final String AI_ML = "AI_ML";
    public static final String DATABASE = "DATABASE";
    public static final String MESSAGING = "MESSAGING";
    public static final String OBSERVABILITY = "OBSERVABILITY";
    /** No keyword in the table matched — never guessed into an arbitrary bucket. */
    public static final String OTHER = "OTHER";

    private static final Map<String, String> KEYWORD_CATEGORY = buildTable();

    private TechnologyTaxonomy() {}

    /** Deterministic lookup — same input always yields the same category. Never null. */
    public static String categorize(String target) {
        if (target == null) return OTHER;
        return KEYWORD_CATEGORY.getOrDefault(target.toLowerCase(Locale.ROOT).trim(), OTHER);
    }

    private static Map<String, String> buildTable() {
        Map<String, String> m = new LinkedHashMap<>();
        put(m, LANGUAGE, "java", "python", "javascript", "typescript", "kotlin", "go", "golang", "rust",
                "c++", "c#", ".net", "ruby", "php", "scala", "swift");
        put(m, FRAMEWORK, "spring", "spring boot", "spring cloud", "react", "angular", "vue", "django",
                "flask", "fastapi", "express", "next.js", ".net core", "hibernate", "jpa");
        put(m, CLOUD, "aws", "azure", "gcp", "google cloud");
        put(m, CONTAINER, "docker", "kubernetes", "k8s");
        put(m, CICD, "jenkins", "ci/cd", "ci", "cd", "github actions", "gitlab ci", "circleci",
                "terraform", "ansible");
        put(m, TESTING, "junit", "selenium", "cypress", "jest");
        put(m, SECURITY, "oauth", "encryption");
        put(m, ARCHITECTURE, "microservices", "system design", "rest", "graphql");
        put(m, AI_ML, "machine learning", "tensorflow", "pytorch");
        put(m, DATABASE, "postgresql", "mysql", "mongodb", "redis", "elasticsearch", "sql", "nosql");
        put(m, MESSAGING, "kafka", "rabbitmq");
        put(m, OBSERVABILITY, "prometheus", "grafana");
        return m;
    }

    private static void put(Map<String, String> m, String category, String... terms) {
        for (String t : terms) m.put(t, category);
    }

    public static List<String> categories() {
        return List.of(LANGUAGE, FRAMEWORK, CLOUD, CONTAINER, CICD, TESTING, SECURITY,
                ARCHITECTURE, AI_ML, DATABASE, MESSAGING, OBSERVABILITY, OTHER);
    }
}
