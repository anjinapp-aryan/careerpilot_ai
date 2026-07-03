package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One provider's listing collapsed into the single canonical schema shared across all sources
 * (Phase 2A Job Lake, Step 5). Derived from a {@link JobRaw} row by the lake normalizer, reusing
 * the same country/city/skill inference as the legacy {@code JobNormalizer}.
 *
 * <p>Separate table from {@code jobs} on purpose: the lake is a SHADOW pipeline that never writes
 * into the live discovered pool, so existing readers are untouched. Unique on
 * {@code (provider, external_job_id)} — re-ingest updates in place.
 */
@Entity
@Table(name = "job_normalized")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JobNormalized {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "raw_id") private UUID rawId;
    @Column(nullable = false) private String provider;
    @Column(name = "external_job_id", nullable = false) private String externalJobId;

    private String title;
    private String company;
    private String country;
    private String city;
    @Column(name = "location_text") private String locationText;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "apply_url") private String applyUrl;
    @Column(name = "salary_min") private BigDecimal salaryMin;
    @Column(name = "salary_max") private BigDecimal salaryMax;
    private Boolean remote;
    @Column(name = "visa_supported") private Boolean visaSupported;
    @Column(name = "posted_date") private Instant postedDate;
    @Column(name = "employment_type") private String employmentType;
    @Column(name = "source_url") private String sourceUrl;

    /** SHA-256 of the normalized identity fields; lets the lake detect content changes cheaply. */
    private String checksum;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
