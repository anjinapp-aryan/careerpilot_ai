package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Phase 6.6 — the single latest computed career strategy/probability snapshot for a user. */
@Entity
@Table(name = "career_strategy")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CareerStrategy {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true) private UUID userId;
    @Column(name = "career_success_probability") private BigDecimal careerSuccessProbability;
    @Column(name = "interview_probability") private BigDecimal interviewProbability;
    @Column(name = "offer_probability") private BigDecimal offerProbability;
    @Column(name = "career_growth_probability") private BigDecimal careerGrowthProbability;
    @Column(name = "market_demand_score") private BigDecimal marketDemandScore;
    @Column(name = "recommended_trajectory", columnDefinition = "text") private String recommendedTrajectory;
    @Column(name = "computed_at") private Instant computedAt;

    // ── Gap C — Career Coach roadmap persistence (V61, additive) ──────────────────────────────
    // Populated by CareerRoadmapPersistenceService from the LangGraph career_strategy agent's
    // horizoned roadmap, behind career.roadmap.persistence.enabled (default off).
    @Column(name = "roadmap_3_month", columnDefinition = "text") private String roadmap3Month;
    @Column(name = "roadmap_6_month", columnDefinition = "text") private String roadmap6Month;
    @Column(name = "roadmap_12_month", columnDefinition = "text") private String roadmap12Month;
    @Column(name = "skill_gaps_json", columnDefinition = "text") private String skillGapsJson;

    // ── Phase 7.19 — Career Goal Intelligence (V66, additive). Four independently-flagged writers,
    // same upsert-into-one-row pattern as the two writers above — never a parallel "strategy" table. ──
    /** {@code SkillGapIntelligenceService} output — strengths/weak/missing/critical skills, deterministic. */
    @Column(name = "skill_gap_intelligence_json", columnDefinition = "text") private String skillGapIntelligenceJson;
    /** {@code PromotionReadinessService} output — per-level, per-dimension readiness; NOT_COMPUTED where ungrounded. */
    @Column(name = "promotion_readiness_json", columnDefinition = "text") private String promotionReadinessJson;
    /** {@code CareerGoalPlannerService} output — current/target/gap/plan for one selected goal. */
    @Column(name = "career_goal_json", columnDefinition = "text") private String careerGoalJson;
    /** {@code CareerRoadmapGeneratorService} output — evidence-grounded roadmap, distinct from the LLM-authored roadmap3/6/12Month above. */
    @Column(name = "computed_roadmap_json", columnDefinition = "text") private String computedRoadmapJson;
    @Column(name = "career_goal_computed_at") private java.time.Instant careerGoalComputedAt;
}
