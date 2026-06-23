package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Job {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "org_id") private UUID orgId;
    @Column(nullable = false) private String title;
    @Column(nullable = false) private String company;
    private String location;
    @Column(nullable = false, columnDefinition = "text") private String description;
    @Column(name = "salary_range") private String salaryRange;
    private String source;
    @Column(name = "external_url") private String externalUrl;
    @Column(name = "posted_at") private Instant postedAt;

    // ── Phase 2 Job Discovery metadata (all nullable; populated only for ingested jobs) ──
    @Column(name = "external_id") private String externalId;
    private String country;
    private String city;
    @Column(name = "salary_min") private BigDecimal salaryMin;
    @Column(name = "salary_max") private BigDecimal salaryMax;
    private String currency;
    private Boolean remote;
    @Column(columnDefinition = "text") private String skills;
    @Column(name = "source_url", length = 1000) private String sourceUrl;
    @Column(name = "posted_date") private Instant postedDate;

    // ── Recommendation-engine enrichment (all nullable; keyword-derived at ingest) ──
    @Column(name = "remote_type") private String remoteType;            // REMOTE | HYBRID | ONSITE
    @Column(name = "sponsorship_available") private Boolean sponsorshipAvailable;
    @Column(name = "relocation_support") private Boolean relocationSupport;
    @Column(name = "company_size") private String companySize;          // STARTUP | SMB | MID | ENTERPRISE
    @Column(name = "required_experience") private Integer requiredExperience;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
