package ai.careerpilot.learning.career.goal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 7.19.4 — Career Roadmap Generator. Deliberately distinct from the LLM-authored {@code
 * roadmap3Month}/{@code roadmap6Month}/{@code roadmap12Month} fields {@code
 * CareerRoadmapPersistenceService} already writes (from the LangGraph career_strategy agent) —
 * this one is assembled purely from {@link SkillGapIntelligenceService} and {@link
 * PromotionReadinessService} output, so every line item traces back to real computed evidence.
 * Written to the SEPARATE {@code computedRoadmapJson} column — never overwrites the existing
 * LLM-authored roadmap.
 *
 * <p>Honest scoping: the spec asks for 12 roadmap sections (Learning/Projects/OpenSource/
 * InterviewPrep/ResumeImprovements/Certifications/Networking/TargetCompanies/Applications/
 * PromotionPrep/SalaryPrep/OfferStrategy). Only the ones with a real backing data source are
 * populated here (learning priorities, certification/project suggestions, promotion-readiness
 * targets). Open source, networking, resume-specific actions, salary preparation, and offer
 * strategy have no computed data source in this platform and are intentionally omitted rather
 * than filled with generic filler.
 */
@Service
public class CareerRoadmapGeneratorService {

    private final SkillGapIntelligenceService skillGap;
    private final PromotionReadinessService promotionReadiness;
    private final boolean enabled;

    public CareerRoadmapGeneratorService(SkillGapIntelligenceService skillGap,
                                         PromotionReadinessService promotionReadiness,
                                         @Value("${career.roadmap-generator.enabled:false}") boolean enabled) {
        this.skillGap = skillGap;
        this.promotionReadiness = promotionReadiness;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> generate(UUID userId) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enabled) return out;

        Map<String, Object> skillGapResult = skillGap.compute(userId);
        Map<String, Object> readinessResult = promotionReadiness.compute(userId);

        List<String> learningPriority = (List<String>) skillGapResult.getOrDefault("learningPriority", List.of());
        List<String> critical = (List<String>) skillGapResult.getOrDefault("criticalSkills", List.of());
        List<String> optional = (List<String>) skillGapResult.getOrDefault("optionalSkills", List.of());
        Map<String, Object> suggestions = (Map<String, Object>) skillGapResult.getOrDefault("suggestions", Map.of());
        Map<String, Object> byLevel = (Map<String, Object>) readinessResult.getOrDefault("readinessByLevel", Map.of());

        List<String> today = new ArrayList<>();
        today.add(critical.isEmpty()
                ? "No critical skill gaps identified from your recent job matches"
                : "Review your " + critical.size() + " critical skill gap(s): " + String.join(", ", critical));

        List<String> nextMonth = new ArrayList<>();
        for (String skill : learningPriority.stream().limit(2).toList()) {
            Object s = suggestions.get(skill);
            if (s instanceof Map<?, ?> m) nextMonth.add("Start: " + m.get("project") + " (" + skill + ")");
        }
        if (nextMonth.isEmpty()) nextMonth.add("No priority skills identified yet — apply to more jobs to build signal");

        List<String> threeMonths = new ArrayList<>();
        for (String skill : learningPriority) {
            Object s = suggestions.get(skill);
            if (s instanceof Map<?, ?> m) threeMonths.add("Certification target: " + m.get("certification") + " (" + skill + ")");
        }
        if (threeMonths.isEmpty()) threeMonths.add("No certification targets identified yet");

        List<String> sixMonths = new ArrayList<>();
        byLevel.entrySet().stream()
                .filter(e -> e.getValue() instanceof Map<?, ?> m && m.containsKey("readinessScore"))
                .findFirst()
                .ifPresentOrElse(
                        e -> sixMonths.add("Promotion readiness for " + e.getKey() + ": currently "
                                + ((Map<?, ?>) e.getValue()).get("readinessScore") + "/100"),
                        () -> sixMonths.add("Promotion readiness not yet computable — see Promotion Readiness for what's missing"));

        List<String> twelveMonths = new ArrayList<>();
        twelveMonths.add(optional.isEmpty()
                ? "No optional/nice-to-have skill gaps identified yet"
                : "Consider broadening into: " + String.join(", ", optional));

        out.put("today", today);
        out.put("nextMonth", nextMonth);
        out.put("threeMonths", threeMonths);
        out.put("sixMonths", sixMonths);
        out.put("twelveMonths", twelveMonths);
        out.put("omittedSections", List.of("openSource", "networking", "resumeImprovements", "salaryPreparation", "offerStrategy"));
        out.put("omittedSectionsReason", "no computed data source exists for these in this platform yet");
        out.put("sourceModules", List.of("SkillGapIntelligenceService", "PromotionReadinessService"));
        return out;
    }
}
