package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2E.7 — an append-only status-timeline entry for a submitted application. SEPARATE from the
 * org-scoped {@link Application} kanban row (linked via {@code applicationId}; that row is never
 * overwritten by tracking). Each status change adds a new row.
 */
@Entity
@Table(name = "application_tracking")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationTracking {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_VIEWED = "VIEWED";
    public static final String STATUS_UNDER_REVIEW = "UNDER_REVIEW";
    public static final String STATUS_INTERVIEW = "INTERVIEW";
    public static final String STATUS_ASSESSMENT = "ASSESSMENT";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_OFFER = "OFFER";
    public static final String STATUS_HIRED = "HIRED";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "application_execution_id") private UUID applicationExecutionId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "application_id") private UUID applicationId;
    @Column(nullable = false) private String status;
    @Column(columnDefinition = "text") private String note;

    @CreationTimestamp @Column(name = "changed_at", updatable = false) private Instant changedAt;
}
