package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.1 — append-only record of one validated status transition of an {@link ApplicationLifecycle}.
 */
@Entity
@Table(name = "application_status_history")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationStatusHistory {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "lifecycle_id", nullable = false) private UUID lifecycleId;
    @Column(name = "from_status") private String fromStatus;
    @Column(name = "to_status", nullable = false) private String toStatus;
    private String note;

    @CreationTimestamp @Column(name = "changed_at", updatable = false) private Instant changedAt;
}
