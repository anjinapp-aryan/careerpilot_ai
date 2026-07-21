package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2E.6 — append-only decision log for how one failed execution attempt was classified and
 * handled by the {@link ai.careerpilot.execution.retry.RetryPolicyService}. HIGH RISK — the policy
 * caps at 3 retries and NEVER retries a validation/duplicate failure (those STOP), so the engine
 * cannot loop endlessly against an external site.
 */
@Entity
@Table(name = "application_retry")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationRetry {

    // failure classes
    public static final String CLASS_NETWORK = "NETWORK";
    public static final String CLASS_CAPTCHA = "CAPTCHA";
    public static final String CLASS_LOGIN_FAILED = "LOGIN_FAILED";
    public static final String CLASS_VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String CLASS_DUPLICATE = "DUPLICATE";
    public static final String CLASS_RATE_LIMITED = "RATE_LIMITED";
    public static final String CLASS_UNKNOWN = "UNKNOWN";
    // Phase 7.16.1 — verification-specific failure classes, reused by RetryPolicyService rather
    // than a separate verification-only retry mechanism.
    /** A submission may well have gone through, but no evidence could be captured — needs a human, never auto-retried (retrying risks a real duplicate). */
    public static final String CLASS_CONFIRMATION_MISSING = "CONFIRMATION_MISSING";
    /** The ATS/connector itself reported an error distinct from a network failure. */
    public static final String CLASS_ATS_ERROR = "ATS_ERROR";
    /** The browser automation layer (Playwright) failed independent of the target site. */
    public static final String CLASS_BROWSER_FAILURE = "BROWSER_FAILURE";
    /** The resolved ATSConnector itself failed (e.g. threw during verifySubmission). */
    public static final String CLASS_PROVIDER_FAILURE = "PROVIDER_FAILURE";

    // actions
    public static final String ACTION_RETRY = "RETRY";
    public static final String ACTION_RETRY_BACKOFF = "RETRY_BACKOFF";
    public static final String ACTION_PAUSE = "PAUSE";
    public static final String ACTION_STOP = "STOP";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "application_execution_id", nullable = false) private UUID applicationExecutionId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(nullable = false) private Integer attempt;
    @Column(name = "failure_class", nullable = false) private String failureClass;
    @Column(nullable = false) private String action;
    @Column(name = "backoff_ms", nullable = false) private Long backoffMs;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
