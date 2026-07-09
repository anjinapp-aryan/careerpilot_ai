package ai.careerpilot.discovery.relevance;

import ai.careerpilot.jobdiscovery.JobTaxonomy;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 3B.1 — {@code overlap = matching skill families / required skill families}, normalized
 * through {@link JobTaxonomy#skillFamily} so surface variants ("Spring" vs "Spring Boot") match,
 * exactly as {@code JobScoring}'s skill sub-score already does. This is a separate, additive
 * computation for the career-relevance feed — it does not read or alter {@code JobScoring}.
 */
@Component
public class SkillOverlapService {

    private final JobTaxonomy taxonomy;

    public SkillOverlapService(JobTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    /** 0-100 overlap percentage: matched candidate skill families / required job skill families. */
    public int overlapPercent(List<String> candidateSkills, List<String> jobSkills) {
        Set<String> candFamilies = taxonomy.skillFamilies(candidateSkills);
        Set<String> jobFamilies = taxonomy.skillFamilies(jobSkills);
        if (jobFamilies.isEmpty()) return 0;

        Set<String> matched = new HashSet<>(candFamilies);
        matched.retainAll(jobFamilies);
        return (int) Math.min(100, Math.round(100.0 * matched.size() / jobFamilies.size()));
    }

    /** Convenience overload for a job's raw comma-joined {@code skills} column. */
    public int overlapPercent(List<String> candidateSkills, String jobSkillsCsv) {
        return overlapPercent(candidateSkills, splitCsv(jobSkillsCsv));
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
