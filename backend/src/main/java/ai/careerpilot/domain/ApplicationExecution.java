package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2E.1 — the state machine for one attempt to EXECUTE (submit) an assembled
 * {@link ApplicationPackage}. HIGH RISK. Nothing in the 2E build actually submits: with the
 * browser layer a throwing stub and all connectors unconfigured, an execution that starts can only
 * reach a terminal {@code ABORTED} ("browser automation disabled") — never {@code SUBMITTED}.
 * Append-only; a retry creates a new row (see {@link ApplicationRetry}).
 */
@Entity
@Table(name = "application_execution")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationExecution {

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_VALIDATING = "VALIDATING";
    public static final String STATUS_EXECUTING = "EXECUTING";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_RETRY = "RETRY";
    public static final String STATUS_ABORTED = "ABORTED";
    /**
     * Gap D — a NEW non-terminal status: the guest-apply form has been filled and a screenshot is
     * parked at {@code ApprovalQueueEntry.TYPE_FORM_SCREENSHOT} awaiting the human "approve this
     * specific filled form" decision. Set by {@code ApplicationExecutionService} when {@code
     * GuestApplyAutomationService#attemptFill} returns AWAITING_APPROVAL; resolved by {@code
     * FormApprovalExecutionWorker} once approved (-> SUBMITTED) or rejected (stays here — a human
     * rejection of the form does not retry automatically).
     */
    public static final String STATUS_AWAITING_APPROVAL = "AWAITING_APPROVAL";

    public static final String TYPE_BROWSER = "BROWSER";
    public static final String TYPE_ATS_CONNECTOR = "ATS_CONNECTOR";
    public static final String TYPE_MANUAL = "MANUAL";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "application_package_id", nullable = false) private UUID applicationPackageId;
    @Column(name = "application_id") private UUID applicationId;

    private String provider;
    @Column(name = "execution_status", nullable = false) private String executionStatus;
    @Column(name = "execution_type", nullable = false) private String executionType;
    @Column(name = "attempt_count", nullable = false) private Integer attemptCount;
    @Column(name = "failure_reason", columnDefinition = "text") private String failureReason;

    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;

    // ── Phase 7.16.1 — submission evidence. Populated only by SubmissionVerificationService,
    // never fabricated: null means "we genuinely don't have this," not "not applicable." ──
    @Column(name = "confirmation_number", columnDefinition = "text") private String confirmationNumber;
    @Column(name = "verification_status") private String verificationStatus;
    @Column(name = "verification_method") private String verificationMethod;
    @Column(name = "verified_at") private Instant verifiedAt;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
