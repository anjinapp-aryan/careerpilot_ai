package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Run-level observability for a lake discovery run (Phase 2A, Step 10): one row per provider per
 * run, correlated by {@code run_id}. Richer than the legacy {@code job_fetch_audit} — it also
 * records {@code duplicates} and {@code failures} so a run's quality is visible at a glance.
 */
@Entity
@Table(name = "job_discovery_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JobDiscoveryAudit {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "run_id") private UUID runId;
    @Column(nullable = false) private String provider;
    @Column(name = "jobs_found", nullable = false) private int jobsFound;
    @Column(name = "jobs_inserted", nullable = false) private int jobsInserted;
    @Column(nullable = false) private int duplicates;
    @Column(nullable = false) private int failures;

    @CreationTimestamp @Column(name = "started_at", updatable = false) private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
}
