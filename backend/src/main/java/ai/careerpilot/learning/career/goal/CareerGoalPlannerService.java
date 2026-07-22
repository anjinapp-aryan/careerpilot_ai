package ai.careerpilot.learning.career.goal;

import ai.careerpilot.companyintel.CompanyKnowledgeService;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.repo.CandidateProfileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 7.19.1 — Career Goal Planner. Orchestrates {@link SkillGapIntelligenceService}, {@link
 * PromotionReadinessService}, and {@link CompanyKnowledgeService} — it computes NOTHING itself
 * beyond level classification and gap arithmetic; every substantive number is delegated to an
 * already-built engine. Supported goals are the named list in {@link CareerLevelTaxonomy}.
 *
 * <p>"Expected salary" is honestly reported as unavailable — no salary-by-target-role/level lookup
 * exists anywhere in this platform (confirmed by architecture review: {@code Offer} rows are
 * per-workflow-run, not indexed by role/level). "Estimated timeline" is explicitly labeled a
 * heuristic (a fixed months-per-level-gap formula), never presented as a data-backed prediction.
 */
@Service
public class CareerGoalPlannerService {

    private final CandidateProfileRepository profiles;
    private final SkillGapIntelligenceService skillGap;
    private final PromotionReadinessService promotionReadiness;
    private final CompanyKnowledgeService companyKnowledge;
    private final boolean enabled;

    public CareerGoalPlannerService(CandidateProfileRepository profiles,
                                    SkillGapIntelligenceService skillGap,
                                    PromotionReadinessService promotionReadiness,
                                    CompanyKnowledgeService companyKnowledge,
                                    @Value("${career.goal-planner.enabled:false}") boolean enabled) {
        this.profiles = profiles;
        this.skillGap = skillGap;
        this.promotionReadiness = promotionReadiness;
        this.companyKnowledge = companyKnowledge;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public List<String> supportedGoals() {
        return CareerLevelTaxonomy.supportedGoals();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> plan(UUID userId, String goalName) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled) return out;

        String targetLevel = CareerLevelTaxonomy.levelForGoal(goalName);
        if (CareerLevelTaxonomy.UNKNOWN.equals(targetLevel)) {
            out.put("error", "unrecognized goal");
            out.put("supportedGoals", supportedGoals());
            return out;
        }

        CandidateProfile profile = profiles.findByUserId(userId).orElse(null);
        String currentLevel = profile != null ? CareerLevelTaxonomy.classify(profile.getCurrentRole()) : CareerLevelTaxonomy.UNKNOWN;
        int currentRank = CareerLevelTaxonomy.rank(currentLevel);
        int targetRank = CareerLevelTaxonomy.rank(targetLevel);
        int gapLevels = currentRank < 0 ? -1 : Math.max(0, targetRank - currentRank);

        Map<String, Object> skillGapResult = skillGap.compute(userId);
        Map<String, Object> readinessResult = promotionReadiness.compute(userId);
        Map<String, Object> readinessByLevel = (Map<String, Object>) readinessResult.getOrDefault("readinessByLevel", Map.of());
        Object estimatedReadiness = readinessByLevel.get(targetLevel);

        List<CompanyKnowledge> known = companyKnowledge.search(userId, null);
        List<String> recommendedCompanies = known.stream()
                .filter(c -> c.getTechnologyMatch() != null && c.getTechnologyMatch() >= 60)
                .sorted(Comparator.comparingInt((CompanyKnowledge c) -> orZero(c.getQualityScore())).reversed())
                .limit(5).map(CompanyKnowledge::getCompanyName).toList();

        String estimatedTimeline = gapLevels <= 0
                ? "Already at or above target level"
                : (gapLevels * 6) + "-" + (gapLevels * 9) + " months (heuristic: ~6-9 months per level gap — not a data-backed prediction)";

        List<String> risks = new ArrayList<>();
        if (currentRank < 0) risks.add("Current role/level could not be classified from the candidate profile — this plan is less reliable");
        if (profile != null && Boolean.TRUE.equals(profile.getVisaRequired())) risks.add("Visa sponsorship required — narrows the eligible company pool");
        if (known.isEmpty()) risks.add("No company knowledge on file yet — recommendations will improve as you research or apply to companies");
        Object criticalSkills = skillGapResult.get("criticalSkills");
        if (criticalSkills instanceof List<?> crit && !crit.isEmpty()) risks.add(crit.size() + " critical skill gap(s) identified");

        out.put("currentPosition", profile != null ? profile.getCurrentRole() : null);
        out.put("currentLevel", currentLevel);
        out.put("targetPosition", goalName);
        out.put("targetLevel", targetLevel);
        out.put("gapLevels", gapLevels);
        out.put("estimatedReadiness", estimatedReadiness);
        out.put("prioritySkills", skillGapResult.get("learningPriority"));
        out.put("recommendedCompanies", recommendedCompanies);
        out.put("estimatedTimeline", estimatedTimeline);
        out.put("riskFactors", risks);
        out.put("expectedSalaryNote", "not available — no salary-by-target-role/level lookup exists in this platform");
        out.put("explainability", Map.of(
                "currentPositionSource", "CandidateProfile.currentRole",
                "gapSource", "CareerLevelTaxonomy rank distance (deterministic keyword classification)",
                "readinessSource", "PromotionReadinessService",
                "prioritySkillsSource", "SkillGapIntelligenceService",
                "recommendedCompaniesSource", "CompanyKnowledgeService — technologyMatch >= 60, ranked by qualityScore"));
        return out;
    }

    private static int orZero(Integer v) {
        return v == null ? 0 : v;
    }
}
