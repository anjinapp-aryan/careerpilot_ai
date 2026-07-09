package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.2 — an append-only, human-readable timeline entry for an application (Applied → Viewed →
 * Assessment → …). Distinct from {@link ApplicationStatusHistory}: history records validated status
 * transitions; the timeline records observable events with a {@code confidence} (some are inferred).
 */
@Entity
@Table(name = "application_timeline")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationTimeline {

    public static final String SOURCE_STATUS_DETECTION = "STATUS_DETECTION";
    public static final String SOURCE_EMAIL = "EMAIL";
    public static final String SOURCE_MANUAL = "MANUAL";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(name = "event_source") private String eventSource;
    private Double confidence;
    @Column(columnDefinition = "text") private String details;

    @CreationTimestamp @Column(name = "occurred_at", updatable = false) private Instant occurredAt;
}
