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
}
