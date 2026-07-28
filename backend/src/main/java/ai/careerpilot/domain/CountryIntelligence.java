package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * International Job Discovery Engine, Phase 1 — static, curated per-country reference metrics
 * (NOT live-computed; there is no real data source for e.g. live visa-approval probability).
 * {@code sourceNote} documents the curation provenance for each row. See
 * {@code V70__country_intelligence.sql} for the seeded values and their rationale.
 */
@Entity
@Table(name = "country_intelligence")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CountryIntelligence {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "country_code", nullable = false, unique = true, length = 2)
    private String countryCode;

    @Column(name = "visa_probability_score", nullable = false) private Integer visaProbabilityScore;
    @Column(name = "relocation_difficulty_score", nullable = false) private Integer relocationDifficultyScore;
    @Column(name = "language_requirement_score", nullable = false) private Integer languageRequirementScore;
    @Column(name = "cost_of_living_index", nullable = false) private Integer costOfLivingIndex;
    @Column(name = "expected_savings_score", nullable = false) private Integer expectedSavingsScore;
    @Column(name = "job_stability_score", nullable = false) private Integer jobStabilityScore;
    @Column(name = "tech_market_score", nullable = false) private Integer techMarketScore;
    @Column(name = "principal_engineer_growth_score", nullable = false) private Integer principalEngineerGrowthScore;
    @Column(name = "ai_market_score", nullable = false) private Integer aiMarketScore;

    @Column(name = "source_note", columnDefinition = "text") private String sourceNote;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
