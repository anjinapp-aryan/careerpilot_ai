package ai.careerpilot.discovery.relevance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3B.1 — classifies a job title/role string into a coarse {@link RoleFamily} via keyword
 * matching. Deterministic, AI-free, pure function — mirrors the style of
 * {@link ai.careerpilot.jobdiscovery.JobTaxonomy#classifyFamily}. Excluded-category keywords are
 * checked first so a title like "BIM Coordinator" or "Media Manager" is never miscategorized as a
 * tech family by coincidence.
 *
 * <p>Every keyword list is {@code @Value}-configurable (comma-separated override), matching this
 * codebase's existing convention for configurable keyword sets (e.g. {@code JOOBLE_KEYWORDS}).
 */
@Component
public class RoleFamilyResolver {

    private final Map<RoleFamily, List<String>> keywordsByFamily;
    private final List<String> excludedKeywords;

    public RoleFamilyResolver(
            @Value("${jobs.relevance.role-family.java-backend:"
                    + "java developer,senior java developer,java lead,java architect,backend engineer,"
                    + "technical lead,principal engineer,solution architect,software engineer,"
                    + "backend developer,java engineer}") String javaBackendKeywords,
            @Value("${jobs.relevance.role-family.data-engineering:"
                    + "data engineer,spark engineer,big data engineer,data engineering,etl engineer,"
                    + "analytics engineer}") String dataEngineeringKeywords,
            @Value("${jobs.relevance.role-family.devops:"
                    + "devops engineer,platform engineer,sre,site reliability engineer,"
                    + "infrastructure engineer,cloud engineer}") String devopsKeywords,
            @Value("${jobs.relevance.role-family.frontend:"
                    + "react developer,ui engineer,frontend engineer,frontend developer,"
                    + "front end developer,angular developer}") String frontendKeywords,
            @Value("${jobs.relevance.excluded-domains:"
                    + "Media,Hospitality,Customer Service,Construction,Sales,Marketing,HR,Creative,BIM}")
            String excludedKeywords) {
        Map<RoleFamily, List<String>> m = new LinkedHashMap<>();
        m.put(RoleFamily.JAVA_BACKEND, splitLower(javaBackendKeywords));
        m.put(RoleFamily.DATA_ENGINEERING, splitLower(dataEngineeringKeywords));
        m.put(RoleFamily.DEVOPS, splitLower(devopsKeywords));
        m.put(RoleFamily.FRONTEND, splitLower(frontendKeywords));
        this.keywordsByFamily = m;
        this.excludedKeywords = splitLower(excludedKeywords);
    }

    /** Classify a job title (+ optional description fallback) into a {@link RoleFamily}. */
    public RoleFamily resolve(String title, String description) {
        String t = title == null ? "" : title.toLowerCase();
        if (containsAny(t, excludedKeywords)) return RoleFamily.EXCLUDED;

        for (Map.Entry<RoleFamily, List<String>> e : keywordsByFamily.entrySet()) {
            if (containsAny(t, e.getValue())) return e.getKey();
        }

        // Title carried no signal — fall back to description, excluded-check only (title is the
        // authoritative signal for the four named families; description alone is too noisy).
        String d = description == null ? "" : description.toLowerCase();
        if (containsAny(d, excludedKeywords)) return RoleFamily.EXCLUDED;
        return RoleFamily.OTHER;
    }

    /** Convenience overload for a bare title/role string (no description context). */
    public RoleFamily resolve(String title) {
        return resolve(title, null);
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String n : needles) {
            if (!n.isBlank() && haystack.contains(n)) return true;
        }
        return false;
    }

    private static List<String> splitLower(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .toList();
    }
}
