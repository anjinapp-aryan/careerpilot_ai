package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.1 — the canonical lifecycle row for one application, tracking its current position in the
 * Applied → Offer → Accepted/Rejected journey. One row per (user, job); status changes are validated
 * by {@code ApplicationLifecycleService} and recorded append-only in {@link ApplicationStatusHistory}.
 *
 * <p>Deliberately named {@code application_lifecycle} (not {@code application_tracking}) to avoid
 * colliding with the Phase 2E execution-tracking table of that name — this is the richer, user-facing
 * "Career OS" lifecycle, separate from 2E's post-submission execution timeline.
 */
@Entity
@Table(name = "application_lifecycle")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationLifecycle {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_VIEWED = "VIEWED";
    public static final String STATUS_UNDER_REVIEW = "UNDER_REVIEW";
    public static final String STATUS_ASSESSMENT = "ASSESSMENT";
    public static final String STATUS_TECHNICAL_INTERVIEW = "TECHNICAL_INTERVIEW";
    public static final String STATUS_MANAGER_INTERVIEW = "MANAGER_INTERVIEW";
    public static final String STATUS_SYSTEM_DESIGN = "SYSTEM_DESIGN";
    public static final String STATUS_HR_INTERVIEW = "HR_INTERVIEW";
    public static final String STATUS_FINAL_ROUND = "FINAL_ROUND";
    public static final String STATUS_OFFER_RECEIVED = "OFFER_RECEIVED";
    public static final String STATUS_NEGOTIATION = "NEGOTIATION";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "application_id") private UUID applicationId;
    private String company;
    private String country;
    @Column(name = "application_date") private Instant applicationDate;
    @Column(name = "current_status", nullable = false) private String currentStatus;
    @Column(name = "previous_status") private String previousStatus;
    private String source;
    @Column(columnDefinition = "text") private String metadata;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
