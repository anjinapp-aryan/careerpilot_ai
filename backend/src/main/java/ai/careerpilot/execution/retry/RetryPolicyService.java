package ai.careerpilot.execution.retry;

import ai.careerpilot.domain.ApplicationRetry;
import ai.careerpilot.repo.ApplicationRetryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Phase 2E.6 — the deterministic Retry & Recovery policy. HIGH RISK: this is what stops the engine
 * from hammering an external site. {@link #decide} is a PURE function (no I/O) so it is exhaustively
 * unit-testable; {@link #classify} maps an error message to a failure class by keyword.
 *
 * <p>Failure-class -&gt; action:
 * <pre>
 *   NETWORK            -> RETRY            (bounded by maxAttempts)
 *   RATE_LIMITED       -> RETRY_BACKOFF    (exponential backoff, bounded by maxAttempts)
 *   CAPTCHA            -> PAUSE            (needs a human; never auto-retried)
 *   LOGIN_FAILED       -> PAUSE            (needs a human; never auto-retried)
 *   VALIDATION_FAILED  -> STOP            (retrying can't fix bad data)
 *   DUPLICATE          -> STOP            (must never resubmit)
 *   UNKNOWN            -> STOP            (fail closed)
 * </pre>
 * Once {@code attempt >= maxAttempts} (default 3), a would-be RETRY becomes STOP — the engine can
 * never loop endlessly.
 */
@Service
public class RetryPolicyService {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicyService.class);

    private final ApplicationRetryRepository retries;
    private final RetryMetrics metrics;
    private final int maxAttempts;
    private final long baseBackoffMs;

    public RetryPolicyService(ApplicationRetryRepository retries, RetryMetrics metrics,
                              @Value("${application.retry.max-attempts:3}") int maxAttempts,
                              @Value("${application.retry.base-backoff-ms:1000}") long baseBackoffMs) {
        this.retries = retries;
        this.metrics = metrics;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** Classify a raw failure reason into a {@code CLASS_*} bucket by keyword. Never null. */
    public String classify(String failureReason) {
        String r = failureReason == null ? "" : failureReason.toLowerCase(Locale.ROOT);
        if (r.contains("timeout") || r.contains("connection reset") || r.contains("network")
                || r.contains("unreachable") || r.contains("socket")) {
            return ApplicationRetry.CLASS_NETWORK;
        }
        if (r.contains("captcha") || r.contains("recaptcha") || r.contains("challenge")) {
            return ApplicationRetry.CLASS_CAPTCHA;
        }
        if (r.contains("login") || r.contains("unauthorized") || r.contains("401")
                || r.contains("authentication") || r.contains("credentials")) {
            return ApplicationRetry.CLASS_LOGIN_FAILED;
        }
        if (r.contains("duplicate") || r.contains("already applied") || r.contains("already submitted")) {
            return ApplicationRetry.CLASS_DUPLICATE;
        }
        if (r.contains("rate limit") || r.contains("429") || r.contains("too many requests")) {
            return ApplicationRetry.CLASS_RATE_LIMITED;
        }
        if (r.contains("validation") || r.contains("invalid") || r.contains("missing field")
                || r.contains("required field")) {
            return ApplicationRetry.CLASS_VALIDATION_FAILED;
        }
        return ApplicationRetry.CLASS_UNKNOWN;
    }

    /**
     * Pure decision: given a failure class and the attempt number just made (1-based), decide the
     * action and backoff. No I/O — safe to call from tests directly.
     */
    public RetryDecision decide(String failureClass, int attempt) {
        String cls = failureClass == null ? ApplicationRetry.CLASS_UNKNOWN : failureClass;
        boolean attemptsRemain = attempt < maxAttempts;
        return switch (cls) {
            case ApplicationRetry.CLASS_NETWORK -> attemptsRemain
                    ? new RetryDecision(cls, ApplicationRetry.ACTION_RETRY, 0L)
                    : new RetryDecision(cls, ApplicationRetry.ACTION_STOP, 0L);
            case ApplicationRetry.CLASS_RATE_LIMITED -> attemptsRemain
                    ? new RetryDecision(cls, ApplicationRetry.ACTION_RETRY_BACKOFF, backoffFor(attempt))
                    : new RetryDecision(cls, ApplicationRetry.ACTION_STOP, 0L);
            case ApplicationRetry.CLASS_CAPTCHA, ApplicationRetry.CLASS_LOGIN_FAILED ->
                    new RetryDecision(cls, ApplicationRetry.ACTION_PAUSE, 0L);
            default -> // VALIDATION_FAILED, DUPLICATE, UNKNOWN
                    new RetryDecision(cls, ApplicationRetry.ACTION_STOP, 0L);
        };
    }

    /** Exponential backoff: base * 2^(attempt-1). */
    private long backoffFor(int attempt) {
        int shift = Math.max(0, attempt - 1);
        return baseBackoffMs * (1L << Math.min(shift, 16)); // cap the shift to avoid overflow
    }

    /** Classify + decide + persist an {@link ApplicationRetry} row + tally metrics. Never throws. */
    @Transactional
    public RetryDecision handleFailure(UUID executionId, UUID userId, UUID jobId,
                                       int attempt, String failureReason) {
        metrics.recordDecision();
        String cls = classify(failureReason);
        RetryDecision decision = decide(cls, attempt);
        try {
            retries.save(ApplicationRetry.builder()
                    .applicationExecutionId(executionId).userId(userId).jobId(jobId)
                    .attempt(attempt).failureClass(decision.failureClass())
                    .action(decision.action()).backoffMs(decision.backoffMs())
                    .build());
        } catch (Exception e) {
            log.warn("RETRY persist error exec={}: {}", executionId, e.toString());
        }
        switch (decision.action()) {
            case ApplicationRetry.ACTION_RETRY, ApplicationRetry.ACTION_RETRY_BACKOFF -> metrics.recordRetry();
            case ApplicationRetry.ACTION_PAUSE -> metrics.recordPause();
            default -> metrics.recordStop();
        }
        if (decision.isTerminal() && !decision.shouldRetry() && attempt >= maxAttempts) {
            metrics.recordExhausted();
        }
        log.info("RETRY_DECISION exec={} attempt={} class={} action={} backoffMs={}",
                executionId, attempt, decision.failureClass(), decision.action(), decision.backoffMs());
        return decision;
    }
}
