package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.1 — append-only audit trail for lifecycle mutations (who/what/when), separate from the
 * business {@link ApplicationStatusHistory} so audit retention is independent of status modelling.
 */
@Entity
@Table(name = "application_lifecycle_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationLifecycleAudit {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "lifecycle_id", nullable = false) private UUID lifecycleId;
    @Column(nullable = false) private String action;
    private String actor;
    @Column(columnDefinition = "text") private String detail;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
