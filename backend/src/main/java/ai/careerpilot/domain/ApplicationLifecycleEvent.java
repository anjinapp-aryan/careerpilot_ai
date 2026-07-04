package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.1 — a generic domain event observed for an application (distinct from Spring application
 * events): e.g. an email arrived, a status was detected. Append-only, per {@link ApplicationLifecycle}.
 */
@Entity
@Table(name = "application_lifecycle_event")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationLifecycleEvent {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "lifecycle_id", nullable = false) private UUID lifecycleId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(name = "event_source") private String eventSource;
    @Column(columnDefinition = "text") private String details;

    @CreationTimestamp @Column(name = "occurred_at", updatable = false) private Instant occurredAt;
}
