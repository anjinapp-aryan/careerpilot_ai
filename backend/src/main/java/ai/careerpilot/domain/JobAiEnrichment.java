package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Semantic, LLM-derived enrichment for a discovered {@link Job} (Phase 2 Increment B).
 * One row per job ({@code job_id} unique). Holds only what the cheap keyword tier
 * ({@code JobEnricher}) cannot reliably derive — normalized seniority, canonical skills,
 * industry domains, an estimated salary band, and a short summary — plus attribution
 * metadata (model, version, fingerprint).
 *
 * <p>Deliberately a separate table, not columns on {@code jobs}: the daily
 * {@code JobNormalizer.merge()} re-upsert only rewrites {@code jobs} columns, so keeping
 * enrichment here means a re-fetch can never wipe an expensive LLM result.
 *
 * <p>List-shaped fields are JSONB persisted as JSON strings via {@code @JdbcTypeCode(SqlTypes.JSON)},
 * mirroring {@link CandidateProfile}. Produced-but-not-consumed in v1 (matching does not read this).
 */
@Entity
@Table(name = "job_ai_enrichment")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JobAiEnrichment {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "job_id", nullable = false, unique = true) private UUID jobId;

    @Column(name = "seniority_level") private String seniorityLevel;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "normalized_skills", columnDefinition = "jsonb")
    private String normalizedSkillsJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "domains", columnDefinition = "jsonb")
    private String domainsJson;

    @Column(name = "employment_type") private String employmentType;
    @Column(name = "salary_band_min") private BigDecimal salaryBandMin;
    @Column(name = "salary_band_max") private BigDecimal salaryBandMax;
    @Column(name = "salary_currency") private String salaryCurrency;
    @Column(name = "salary_estimated") private Boolean salaryEstimated;

    @Column(columnDefinition = "text") private String summary;
    @Column(name = "confidence_score") private BigDecimal confidenceScore;

    @Column private String model;
    @Column(name = "enrichment_version") private Integer enrichmentVersion;
    @Column(name = "content_fingerprint") private String contentFingerprint;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
