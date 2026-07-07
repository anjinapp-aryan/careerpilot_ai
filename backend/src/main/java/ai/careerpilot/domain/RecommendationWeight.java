package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 6.4 — a learned boost/penalty for one (user, dimension, key). Computed from
 * {@link SuccessPattern}/{@link FailurePattern}; NOT wired into {@code JobScoring}/{@code JobMatchingService}
 * in this pass (see {@code AdaptiveRecommendationEngine} javadoc) — purely additive and inert today.
 */
@Entity
@Table(name = "recommendation_weight")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RecommendationWeight {

    public static final String DIM_COMPANY = "COMPANY";
    public static final String DIM_ROLE = "ROLE";
    public static final String DIM_SKILL = "SKILL";
    public static final String DIM_INDUSTRY = "INDUSTRY";
    public static final String DIM_LOCATION = "LOCATION";
    public static final String DIM_SALARY = "SALARY";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false) private String dimension;
    @Column(name = "dimension_key", nullable = false) private String dimensionKey;
    @Builder.Default @Column(nullable = false) private int boost = 0;
    @Column(columnDefinition = "text") private String reason;
    @Column(name = "computed_at") private Instant computedAt;
}
