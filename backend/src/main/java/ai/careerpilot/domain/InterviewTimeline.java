package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 3A.4 — append-only per-round event stream for an {@link Interview} (scheduled/rescheduled/completed). */
@Entity
@Table(name = "interview_timeline")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InterviewTimeline {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "interview_id", nullable = false) private UUID interviewId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(columnDefinition = "text") private String details;

    @CreationTimestamp @Column(name = "occurred_at", updatable = false) private Instant occurredAt;
}
