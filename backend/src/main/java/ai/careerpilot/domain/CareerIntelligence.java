package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.6 — an append-only per-user learned probability for one dimension (overall career success,
 * interview/offer probability, or a per-country/company/technology/role success rate). Computed
 * deterministically from {@link ApplicationAnalytics} snapshots; no LLM.
 */
@Entity
@Table(name = "career_intelligence")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CareerIntelligence {

    public static final String DIM_CAREER_SUCCESS = "CAREER_SUCCESS";
    public static final String DIM_INTERVIEW_PROBABILITY = "INTERVIEW_PROBABILITY";
    public static final String DIM_OFFER_PROBABILITY = "OFFER_PROBABILITY";
    public static final String DIM_COUNTRY = "COUNTRY_SUCCESS";
    public static final String DIM_COMPANY = "COMPANY_SUCCESS";
    public static final String DIM_TECHNOLOGY = "TECHNOLOGY_SUCCESS";
    public static final String DIM_ROLE = "ROLE_SUCCESS";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false) private String dimension;
    @Column(name = "dimension_key") private String dimensionKey;
    @Column(precision = 6, scale = 4) private BigDecimal probability;
    @Column(name = "sample_size") private Integer sampleSize;

    @CreationTimestamp @Column(name = "computed_at", updatable = false) private Instant computedAt;
}
