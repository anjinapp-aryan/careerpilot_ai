package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per provider failure during a lake discovery run (Phase 2A, Step 10). A failing provider
 * is isolated — its error is recorded here and the run continues with the next provider, mirroring
 * the legacy {@code JobAggregationService} per-provider isolation.
 */
@Entity
@Table(name = "job_discovery_failure")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JobDiscoveryFailure {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "run_id") private UUID runId;
    @Column(nullable = false) private String provider;
    @Column(columnDefinition = "text") private String error;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
