package ai.careerpilot.learning.career.goal;

import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.Interview;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.InterviewRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 7.19.3 — Promotion Readiness. Zero prior art existed for this (confirmed by architecture
 * review). Of the 12 dimensions the spec names, only 5 have ANY real backing data source in this
 * codebase today: technicalDepth/careerMaturity (years experience), cloud/leadership (booleans on
 * {@link CandidateProfile}), and systemDesign (real {@link Interview} outcomes, now computable
 * since Phase 7.17.2 closed the result-inference gap). Architecture, DevOps, communication,
 * mentoring, business knowledge, ownership, and decision-making have NO data source anywhere in
 * this platform — every one of them returns {@code NOT_COMPUTED} with an explicit reason, never a
 * guessed value. The overall readiness score is honest about how many dimensions actually fed it.
 */
@Service
public class PromotionReadinessService {

    private static final List<String> TARGET_LEVELS = List.of(
            CareerLevelTaxonomy.SENIOR, CareerLevelTaxonomy.TECH_LEAD, CareerLevelTaxonomy.ARCHITECT,
            CareerLevelTaxonomy.STAFF, CareerLevelTaxonomy.PRINCIPAL, CareerLevelTaxonomy.ENGINEERING_MANAGER);

    private static final List<String> UNGROUNDED_DIMENSIONS = List.of(
            "architecture", "devops", "communication", "mentoring", "businessKnowledge", "ownership", "decisionMaking");

    private final CandidateProfileRepository profiles;
    private final InterviewRepository interviews;
    private final boolean enabled;

    public PromotionReadinessService(CandidateProfileRepository profiles, InterviewRepository interviews,
                                     @Value("${career.promotion-readiness.enabled:false}") boolean enabled) {
        this.profiles = profiles;
        this.interviews = interviews;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Map<String, Object> compute(UUID userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled) return out;

        CandidateProfile profile = profiles.findByUserId(userId).orElse(null);
        List<Interview> allInterviews = interviews.findByUserIdOrderByCreatedAtDesc(userId);
        Map<String, Object> dimensions = computeDimensions(profile, allInterviews);
        long computedCount = dimensions.values().stream()
                .filter(v -> v instanceof Map<?, ?> m && !"NOT_COMPUTED".equals(m.get("value"))).count();

        String currentLevel = profile != null ? CareerLevelTaxonomy.classify(profile.getCurrentRole()) : CareerLevelTaxonomy.UNKNOWN;
        int currentRank = CareerLevelTaxonomy.rank(currentLevel);

        Map<String, Object> byLevel = new LinkedHashMap<>();
        for (String level : TARGET_LEVELS) {
            byLevel.put(level, evaluateLevel(dimensions, currentRank, CareerLevelTaxonomy.rank(level)));
        }

        out.put("currentLevel", currentLevel);
        out.put("dimensions", dimensions);
        out.put("dimensionsComputed", computedCount);
        out.put("dimensionsTotal", dimensions.size());
        out.put("readinessByLevel", byLevel);
        return out;
    }

    private Map<String, Object> evaluateLevel(Map<String, Object> dimensions, int currentRank, int targetRank) {
        Map<String, Object> level = new LinkedHashMap<>();
        if (currentRank < 0) {
            level.put("readiness", "NOT_COMPUTED");
            level.put("reason", "current level unknown — no candidate profile or unrecognized current-role title");
            return level;
        }
        if (targetRank <= currentRank) {
            level.put("readiness", "ALREADY_AT_OR_ABOVE_TARGET_LEVEL");
            return level;
        }
        List<Integer> scores = dimensions.values().stream()
                .filter(v -> v instanceof Map<?, ?> m && !"NOT_COMPUTED".equals(m.get("value")))
                .map(v -> scoreOf((String) ((Map<?, ?>) v).get("value")))
                .toList();
        if (scores.isEmpty()) {
            level.put("readiness", "NOT_COMPUTED");
            level.put("reason", "no dimensions were computable for this candidate");
            return level;
        }
        double avg = scores.stream().mapToInt(Integer::intValue).average().orElse(0);
        int gapPenalty = Math.max(0, (targetRank - currentRank - 1)) * 10; // more than one rung away costs more
        int readiness = (int) Math.max(0, Math.min(100, Math.round(avg) - gapPenalty));
        level.put("readinessScore", readiness);
        level.put("basedOnDimensions", scores.size());
        level.put("evidence", scores.size() + " of " + dimensions.size() + " dimensions were computable from real data");
        return level;
    }

    private static int scoreOf(String value) {
        return switch (value) {
            case "HIGH" -> 90;
            case "MEDIUM" -> 60;
            case "LOW" -> 25;
            default -> 50;
        };
    }

    private Map<String, Object> computeDimensions(CandidateProfile profile, List<Interview> allInterviews) {
        Map<String, Object> d = new LinkedHashMap<>();

        Integer years = profile == null ? null : profile.getYearsExperience();
        d.put("technicalDepth", years == null ? notComputed("no years-of-experience data on file")
                : grounded(tierForYears(years), years + " years experience on file"));
        d.put("careerMaturity", years == null ? notComputed("no years-of-experience data on file")
                : grounded(tierForYears(years), years + " years experience on file"));

        Boolean cloud = profile == null ? null : profile.getCloudExpertise();
        d.put("cloud", cloud == null ? notComputed("no cloud-expertise signal on candidate profile")
                : grounded(cloud ? "HIGH" : "LOW", "candidate profile cloudExpertise flag = " + cloud));

        Boolean leadership = profile == null ? null : profile.getLeadershipExperience();
        d.put("leadership", leadership == null ? notComputed("no leadership signal on candidate profile")
                : grounded(leadership ? "HIGH" : "LOW", "candidate profile leadershipExperience flag = " + leadership));

        List<Interview> systemDesign = allInterviews.stream()
                .filter(i -> Interview.TYPE_SYSTEM_DESIGN.equals(i.getInterviewType())).toList();
        long decided = systemDesign.stream()
                .filter(i -> Interview.RESULT_PASSED.equals(i.getResult()) || Interview.RESULT_FAILED.equals(i.getResult()))
                .count();
        if (decided == 0) {
            d.put("systemDesign", notComputed("no system-design interview with a recorded outcome on file"));
        } else {
            long passed = systemDesign.stream().filter(i -> Interview.RESULT_PASSED.equals(i.getResult())).count();
            d.put("systemDesign", grounded(passed * 100.0 / decided >= 50 ? "HIGH" : "LOW",
                    decided + " system-design interview(s) with a recorded outcome, " + passed + " passed"));
        }

        for (String dim : UNGROUNDED_DIMENSIONS) {
            d.put(dim, notComputed("no data source exists for this dimension anywhere in this platform"));
        }
        return d;
    }

    private static String tierForYears(int years) {
        return years >= 10 ? "HIGH" : years >= 5 ? "MEDIUM" : "LOW";
    }

    private static Map<String, Object> grounded(String value, String evidence) {
        return Map.of("value", value, "evidence", evidence);
    }

    private static Map<String, Object> notComputed(String reason) {
        return Map.of("value", "NOT_COMPUTED", "evidence", reason);
    }
}
