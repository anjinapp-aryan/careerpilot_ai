package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase F1 — one page of a multi-step employer form, and its approval outcome.
 *
 * <p>Exists because a browser session cannot span a human approval: with one production lease and a
 * 180-second TTL, the context is always released before waiting. Progress therefore lives here, and
 * a resumed run replays the already-approved pages deterministically before touching a new one.
 *
 * <p>Captured evidence ({@code screenshotId}, {@code bundleJson}, {@code pageUrl}) is written once
 * and never rewritten — the record of what a reviewer approved must stay what they actually saw.
 */
@Entity
@Table(name = "execution_step")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExecutionStep {

    /** Filled and screenshotted; waiting on a human. Nothing else may proceed. */
    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    /** The advance guard vetoed leaving this page. */
    public static final String STATUS_BLOCKED = "BLOCKED";
    public static final String STATUS_FAILED = "FAILED";

    @Id @GeneratedValue private UUID id;

    @Column(name = "execution_id", nullable = false) private UUID executionId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "step_number", nullable = false) private int stepNumber;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "page_url", columnDefinition = "text") private String pageUrl;

    @Column(name = "screenshot_id") private UUID screenshotId;
    @Column(name = "approval_queue_entry_id") private UUID approvalQueueEntryId;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "bundle_json") private String bundleJson;

    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "final_step", nullable = false) private boolean finalStep;

    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public boolean isApproved() {
        return STATUS_APPROVED.equals(status);
    }

    public boolean isAwaitingApproval() {
        return STATUS_PENDING_APPROVAL.equals(status);
    }
}
