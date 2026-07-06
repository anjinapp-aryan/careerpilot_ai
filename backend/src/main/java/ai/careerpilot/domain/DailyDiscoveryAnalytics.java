package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Phase 5 — one aggregate or per-user analytics row for a {@link DailyDiscoveryRun}. */
@Entity
@Table(name = "daily_discovery_analytics")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DailyDiscoveryAnalytics {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "run_id", nullable = false) private UUID runId;
    @Column(name = "user_id") private UUID userId; // null = pipeline-wide aggregate row

    @Column(name = "domestic_jobs") private Integer domesticJobs;
    @Column(name = "international_jobs") private Integer internationalJobs;
    @Column(name = "recommended_jobs") private Integer recommendedJobs;
    @Column(name = "must_apply_jobs") private Integer mustApplyJobs;
    @Column(name = "high_priority_jobs") private Integer highPriorityJobs;
    @Column(name = "human_review_jobs") private Integer humanReviewJobs;
    @Column(name = "hidden_jobs") private Integer hiddenJobs;
    @Column(name = "average_score") private BigDecimal averageScore;

    @Column(name = "industry_distribution", columnDefinition = "text") private String industryDistribution;
    @Column(name = "skill_distribution", columnDefinition = "text") private String skillDistribution;
    @Column(name = "company_distribution", columnDefinition = "text") private String companyDistribution;
    @Column(name = "match_strength_distribution", columnDefinition = "text") private String matchStrengthDistribution;

    @CreationTimestamp @Column(name = "computed_at", updatable = false) private Instant computedAt;
}
