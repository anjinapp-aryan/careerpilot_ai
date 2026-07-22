package ai.careerpilot.learning.career.goal;

import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.repo.JobRecommendationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 7.19.2 — Skill Gap Intelligence. Reuses the SAME persisted, deterministic {@code
 * JobScoring} output already sitting in {@code job_recommendations} (matching/missing skills per
 * job, computed by {@code JobMatchingService}) — no re-scoring, no new matching engine. Strengths/
 * gaps are frequency counts across the candidate's own recent job recommendations, not invented.
 *
 * <p>Emerging skills and industry-wide technology trends are explicitly NOT computed — there is no
 * cross-user, time-series technology-trend data source anywhere in this codebase (confirmed by
 * architecture review); returning a number for either would be fabrication.
 */
@Service
public class SkillGapIntelligenceService {

    private final JobRecommendationRepository jobRecommendations;
    private final boolean enabled;

    public SkillGapIntelligenceService(JobRecommendationRepository jobRecommendations,
                                       @Value("${career.skill-gap.enabled:false}") boolean enabled) {
        this.jobRecommendations = jobRecommendations;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Map<String, Object> compute(UUID userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled) return out;

        List<JobRecommendation> recs = jobRecommendations.findByUserIdOrderByMatchScoreDesc(userId);
        int totalJobs = recs.size();
        out.put("sampleSize", totalJobs);
        out.put("evidence", totalJobs + " job recommendations analyzed (matched/missing skills already computed by JobScoring)");
        if (totalJobs == 0) {
            out.put("strengths", List.of());
            out.put("weakSkills", List.of());
            out.put("missingSkills", List.of());
            out.put("criticalSkills", List.of());
            out.put("optionalSkills", List.of());
            out.put("learningPriority", List.of());
            return out;
        }

        Map<String, Long> matchedFreq = new LinkedHashMap<>();
        Map<String, Long> missingFreq = new LinkedHashMap<>();
        for (JobRecommendation r : recs) {
            for (String s : splitCsv(r.getMatchingSkills())) matchedFreq.merge(s, 1L, Long::sum);
            for (String s : splitCsv(r.getMissingSkills())) missingFreq.merge(s, 1L, Long::sum);
        }

        List<String> strengths = topN(matchedFreq, 10);
        // "weak" — skills the candidate does have, but that only matched a small minority of jobs;
        // present, not yet a differentiator.
        List<String> weakSkills = matchedFreq.entrySet().stream()
                .filter(e -> e.getValue() < Math.max(1, totalJobs * 0.3))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey).limit(10).toList();
        List<String> missingSkills = topN(missingFreq, 15);
        List<String> criticalSkills = missingFreq.entrySet().stream()
                .filter(e -> e.getValue() >= Math.max(1, totalJobs * 0.4))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey).limit(10).toList();
        List<String> optionalSkills = missingFreq.entrySet().stream()
                .filter(e -> e.getValue() < Math.max(1, totalJobs * 0.15))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey).limit(10).toList();
        List<String> learningPriority = criticalSkills.stream().limit(5).toList();

        Map<String, Object> suggestions = new LinkedHashMap<>();
        for (String skill : learningPriority) {
            SkillSuggestionTable.Suggestion s = SkillSuggestionTable.suggestionsFor(skill);
            suggestions.put(skill, Map.of(
                    "certification", s.certification(), "project", s.project(), "practice", s.practice()));
        }

        out.put("strengths", strengths);
        out.put("weakSkills", weakSkills);
        out.put("missingSkills", missingSkills);
        out.put("criticalSkills", criticalSkills);
        out.put("optionalSkills", optionalSkills);
        out.put("emergingSkillsNote", "not computed — no market-wide, time-series technology-trend data exists in this platform");
        out.put("industryTrendsNote", "not computed — no cross-user/industry trend aggregation exists");
        out.put("learningPriority", learningPriority);
        out.put("suggestions", suggestions);
        return out;
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return java.util.Arrays.stream(csv.split(",")).map(String::trim).map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    private static List<String> topN(Map<String, Long> freq, int n) {
        return freq.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey).limit(n).toList();
    }
}
