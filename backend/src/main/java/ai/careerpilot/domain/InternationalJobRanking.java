package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * International Job Discovery Engine, Phase 1 — one row per (user, job), the output of {@code
 * InternationalJobScoring}'s weighted formula. Deliberately parallel to {@code
 * job_recommendations} (scoreV2's output), not merged into it — a second, differently-weighted
 * ranking formula stays structurally independent and independently droppable. Written by {@code
 * InternationalJobRankingService}, gated by {@code career.international.ranking.enabled}.
 */
@Entity
@Table(name = "international_job_ranking")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InternationalJobRanking {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;

    @Column(name = "rank_score", nullable = false) private Integer rankScore;
    @Column(name = "skill_score", nullable = false) private Integer skillScore;
    @Column(name = "visa_probability_score", nullable = false) private Integer visaProbabilityScore;
    @Column(name = "salary_score", nullable = false) private Integer salaryScore;
    @Column(name = "career_growth_score", nullable = false) private Integer careerGrowthScore;
    @Column(name = "company_stability_score", nullable = false) private Integer companyStabilityScore;
    @Column(name = "remote_flexibility_score", nullable = false) private Integer remoteFlexibilityScore;
    @Column(name = "principal_engineer_growth_score", nullable = false) private Integer principalEngineerGrowthScore;
    @Column(name = "ai_tech_stack_score", nullable = false) private Integer aiTechStackScore;

    @Column(name = "country_code", length = 2) private String countryCode;
    private String tier;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
