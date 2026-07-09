package ai.careerpilot.companyintel;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Phase 7.13 — the 10 company scores. Pure and deterministic: every score is a clamped 0-100
 * function of observed facts only (knowledge sections present, graph edge counts, real outcome
 * counts, candidate-skill overlap), and every score carries a human-readable explanation. No LLM,
 * no randomness — identical input always yields identical output.
 */
@Component
public class CompanyScoringService {

    /** Observed facts about one company; the only input the scorer sees. */
    public record CompanyStats(
            Map<String, String> sections,
            Map<String, Long> edgeCounts,          // relation type -> distinct target count
            long applications, long interviews, long offers, long rejections,
            Set<String> companyTech,               // TECHNOLOGY + SKILL edge targets (lowercase)
            List<String> candidateSkills) {

        public static CompanyStats empty() {
            return new CompanyStats(Map.of(), Map.of(), 0, 0, 0, 0, Set.of(), List.of());
        }
    }

    public record ScoreSet(Map<String, Integer> scores, Map<String, String> explanations) {}

    public ScoreSet score(CompanyStats s) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        Map<String, String> why = new LinkedHashMap<>();

        int sectionCount = s.sections().size();
        long techEdges = s.edgeCounts().getOrDefault("TECHNOLOGY", 0L) + s.edgeCounts().getOrDefault("SKILL", 0L);

        // Company Quality — knowledge depth + positive outcome history.
        int quality = clamp(30 + sectionCount * 4 + (int) Math.min(20, techEdges)
                + (int) (s.offers() * 10) - (int) (s.rejections() * 2));
        put(scores, why, "qualityScore", quality, sectionCount + " knowledge sections, " + techEdges
                + " tech/skill signals, " + s.offers() + " offers, " + s.rejections() + " rejections observed");

        // Interview Difficulty — grows with observed interviews that did not convert to offers.
        long failedInterviews = Math.max(0, s.interviews() - s.offers());
        int difficulty = clamp(50 + (int) (failedInterviews * 10) - (int) (s.offers() * 10)
                + (s.sections().containsKey(KnowledgeSection.INTERVIEW_PROCESS.key()) ? 5 : 0));
        put(scores, why, "interviewDifficulty", difficulty, s.interviews() + " interviews observed, "
                + s.offers() + " converted to offers");

        // Hiring Probability — real conversion history around this company.
        int hiring = clamp(50 + (int) (s.offers() * 15) + (int) (s.interviews() * 5) - (int) (s.rejections() * 5));
        put(scores, why, "hiringProbability", hiring, "history: " + s.applications() + " applications → "
                + s.interviews() + " interviews → " + s.offers() + " offers (" + s.rejections() + " rejections)");

        // Resume Compatibility / Technology Match — overlap of candidate skills with observed demand.
        int overlapPct = overlapPercent(s.companyTech(), s.candidateSkills());
        put(scores, why, "resumeCompatibility", overlapPct >= 0 ? overlapPct : 50,
                overlapPct >= 0 ? overlapPct + "% of this company's observed skill demand matches the candidate's skills"
                        : "no skill signals observed yet — neutral 50");
        put(scores, why, "technologyMatch", overlapPct >= 0 ? overlapPct : 50,
                overlapPct >= 0 ? "candidate covers " + overlapPct + "% of the observed technology signals"
                        : "no technology signals observed yet — neutral 50");

        // Career Growth — growth-signal knowledge + role breadth in the graph.
        long roleEdges = s.edgeCounts().getOrDefault("ROLE", 0L);
        int growth = clamp(40 + (s.sections().containsKey(KnowledgeSection.GROWTH_SIGNALS.key()) ? 20 : 0)
                + (int) Math.min(25, roleEdges * 5));
        put(scores, why, "careerGrowth", growth, roleEdges + " distinct roles observed"
                + (s.sections().containsKey(KnowledgeSection.GROWTH_SIGNALS.key()) ? ", growth signals on file" : ""));

        // Salary Potential — only meaningful when salary knowledge exists.
        boolean salaryKnown = s.sections().containsKey(KnowledgeSection.SALARY_INSIGHTS.key())
                || s.edgeCounts().getOrDefault("SALARY_BAND", 0L) > 0;
        put(scores, why, "salaryPotential", salaryKnown ? 70 : 50,
                salaryKnown ? "salary insights on file for this company" : "no salary data observed yet — neutral 50");

        // Culture Match — only meaningful when culture/reputation knowledge exists.
        boolean cultureKnown = s.sections().containsKey(KnowledgeSection.CULTURE.key())
                || s.sections().containsKey(KnowledgeSection.REPUTATION.key());
        put(scores, why, "cultureMatch", cultureKnown ? 65 : 50,
                cultureKnown ? "culture/reputation knowledge on file" : "no culture signals observed yet — neutral 50");

        // Learning Value — how much the platform has learned here (events + versions proxy).
        long totalEvents = s.applications() + s.interviews() + s.offers() + s.rejections();
        int learning = clamp(20 + (int) Math.min(50, totalEvents * 10) + sectionCount * 2);
        put(scores, why, "learningValue", learning, totalEvents + " learning outcomes and "
                + sectionCount + " knowledge sections accumulated");

        // Long-term Opportunity — blend of quality, growth and hiring history.
        int longTerm = clamp((quality + growth + hiring) / 3);
        put(scores, why, "longTermOpportunity", longTerm, "average of quality (" + quality + "), growth ("
                + growth + ") and hiring probability (" + hiring + ")");

        return new ScoreSet(scores, why);
    }

    /** -1 when the company has no skill signals yet (callers render neutral). */
    static int overlapPercent(Set<String> companyTech, List<String> candidateSkills) {
        if (companyTech == null || companyTech.isEmpty()) return -1;
        if (candidateSkills == null || candidateSkills.isEmpty()) return 0;
        Set<String> candidate = new HashSet<>();
        for (String s : candidateSkills) {
            if (s != null) candidate.add(s.strip().toLowerCase(Locale.ROOT));
        }
        long matched = companyTech.stream()
                .filter(t -> candidate.stream().anyMatch(c -> !c.isEmpty() && (t.contains(c) || c.contains(t))))
                .count();
        return (int) Math.round(matched * 100.0 / companyTech.size());
    }

    private static void put(Map<String, Integer> scores, Map<String, String> why, String key, int value, String reason) {
        scores.put(key, clamp(value));
        why.put(key, reason);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }
}
