package ai.careerpilot.jobdiscovery.enterprise;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 5.3.1 — persistent record of one company's Enterprise ATS connector. Previously this
 * configuration lived only in CSV env vars ({@code career.discovery.{ats}.companies}, see
 * {@link ai.careerpilot.jobdiscovery.provider.WorkdayCompanyConnector}/
 * {@link ai.careerpilot.jobdiscovery.provider.EnterpriseAtsCompanyConnector}); this table adds an
 * admin-manageable, health-tracked view on top without replacing the CSV as the deploy-time
 * source of truth — {@link CompanyConnectorService} bootstraps rows from CSV the first time the
 * table is empty, then this table is authoritative for enable/disable/health going forward.
 */
@Entity
@Table(name = "company_connectors")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CompanyConnector {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "company_name", nullable = false) private String companyName;
    @Column(name = "ats_type", nullable = false) private String atsType; // WORKDAY | TALEO | SUCCESSFACTORS
    @Column(name = "career_url", columnDefinition = "text") private String careerUrl;

    // Workday-only fields; null for Taleo/SuccessFactors connectors.
    private String tenant;
    private String cluster;
    private String site;

    private String country;
    private String industry;
    @Builder.Default private Integer priority = 0;
    @Builder.Default private Boolean enabled = true;

    @Column(name = "health_status") @Builder.Default private String healthStatus = "NEVER_RUN";
    @Column(name = "last_successful_sync") private Instant lastSuccessfulSync;
    @Column(name = "last_failure") private Instant lastFailure;
    @Column(name = "last_failure_reason", columnDefinition = "text") private String lastFailureReason;
    @Column(name = "failure_count") @Builder.Default private Integer failureCount = 0;
    @Column(name = "average_latency_ms") @Builder.Default private Long averageLatencyMs = 0L;
    @Column(name = "jobs_imported") @Builder.Default private Integer jobsImported = 0;

    // Gap A — Company Discovery Agent (V60, additive). Null for connectors created manually by an
    // admin (Phase 5.3.1 CSV bootstrap / manual insert); set only on rows created by
    // CompanyDiscoveryService after a successful probe. "PENDING_APPROVAL" | "APPROVED" | "REJECTED".
    @Column(name = "discovery_status") private String discoveryStatus;
    @Column(name = "discovered_at") private Instant discoveredAt;
    @Column(name = "discovered_by") private String discoveredBy;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
