package ai.careerpilot.learning.career.goal;

import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.repo.CareerStrategyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 7.19 — the co-writer that upserts {@link SkillGapIntelligenceService}, {@link
 * PromotionReadinessService}, and {@link CareerRoadmapGeneratorService} output into the SAME
 * canonical {@link CareerStrategy} row {@code CareerStrategyEngine}/{@code
 * CareerRoadmapPersistenceService} already write to — same idempotent upsert shape, same
 * repository, same row, never a parallel table. Deliberately NOT wired into {@code
 * CareerLearningWorker}'s event listener (that's gated by {@code learning.adaptive-career.enabled},
 * a different feature entirely) — each of the four Phase 7.19 flags is independent, so this engine
 * is invoked on demand from the API instead (compute-then-cache, same shape as {@code
 * CandidateFitService}/{@code CompanyInterviewIntelligenceService} elsewhere in this codebase).
 */
@Service
public class CareerGoalEngine {

    private final SkillGapIntelligenceService skillGap;
    private final PromotionReadinessService promotionReadiness;
    private final CareerRoadmapGeneratorService roadmapGenerator;
    private final CareerGoalPlannerService goalPlanner;
    private final CareerStrategyRepository strategies;
    private final ObjectMapper mapper = new ObjectMapper();

    public CareerGoalEngine(SkillGapIntelligenceService skillGap, PromotionReadinessService promotionReadiness,
                            CareerRoadmapGeneratorService roadmapGenerator, CareerGoalPlannerService goalPlanner,
                            CareerStrategyRepository strategies) {
        this.skillGap = skillGap;
        this.promotionReadiness = promotionReadiness;
        this.roadmapGenerator = roadmapGenerator;
        this.goalPlanner = goalPlanner;
        this.strategies = strategies;
    }

    /** Recomputes and persists whichever of the three independently-flagged pieces are enabled. */
    @Transactional
    public CareerStrategy recompute(UUID userId) {
        CareerStrategy row = strategies.findByUserId(userId)
                .orElseGet(() -> CareerStrategy.builder().userId(userId).build());
        boolean changed = false;
        if (skillGap.isEnabled()) {
            row.setSkillGapIntelligenceJson(writeJson(skillGap.compute(userId)));
            changed = true;
        }
        if (promotionReadiness.isEnabled()) {
            row.setPromotionReadinessJson(writeJson(promotionReadiness.compute(userId)));
            changed = true;
        }
        if (roadmapGenerator.isEnabled()) {
            row.setComputedRoadmapJson(writeJson(roadmapGenerator.generate(userId)));
            changed = true;
        }
        if (changed) {
            row.setCareerGoalComputedAt(Instant.now());
            return strategies.save(row);
        }
        return row;
    }

    /** Plans one named goal and persists it onto the same row (last-planned goal). */
    @Transactional
    public Map<String, Object> planGoal(UUID userId, String goalName) {
        Map<String, Object> plan = goalPlanner.plan(userId, goalName);
        if (goalPlanner.isEnabled() && !plan.containsKey("error")) {
            CareerStrategy row = strategies.findByUserId(userId)
                    .orElseGet(() -> CareerStrategy.builder().userId(userId).build());
            row.setCareerGoalJson(writeJson(plan));
            row.setCareerGoalComputedAt(Instant.now());
            strategies.save(row);
        }
        return plan;
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
