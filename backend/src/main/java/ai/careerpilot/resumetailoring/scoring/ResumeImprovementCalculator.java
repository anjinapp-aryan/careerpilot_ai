package ai.careerpilot.resumetailoring.scoring;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic (no LLM) ATS-style keyword-coverage score: what fraction of the job's required
 * skills/keywords appear in a resume text, scaled 0-100. Computed once for the original resume
 * ({@code atsBefore}) and once for the tailored resume ({@code atsAfter}) so
 * {@code ResumeTailoringService} can persist {@code improvement_score = after - before}. Mirrors
 * the existing {@code JobScoring} philosophy of simple, explainable, reproducible arithmetic
 * rather than another LLM call for scoring.
 */
@Component
public class ResumeImprovementCalculator {

    public record ImprovementResult(int atsBefore, int atsAfter, int improvementScore) {
    }

    public ImprovementResult calculate(String originalResumeText, String tailoredResumeText, List<String> jobSkills) {
        int before = atsScore(originalResumeText, jobSkills);
        int after = atsScore(tailoredResumeText, jobSkills);
        return new ImprovementResult(before, after, after - before);
    }

    /** Percentage of {@code jobSkills} whose token(s) appear (case-insensitive) in {@code resumeText}. */
    public int atsScore(String resumeText, List<String> jobSkills) {
        if (jobSkills == null || jobSkills.isEmpty()) return 0;
        String haystack = resumeText == null ? "" : resumeText.toLowerCase(Locale.ROOT);
        Set<String> distinctSkills = jobSkills.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        if (distinctSkills.isEmpty()) return 0;
        long matched = distinctSkills.stream().filter(haystack::contains).count();
        return (int) Math.round((matched * 100.0) / distinctSkills.size());
    }
}
