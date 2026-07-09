package ai.careerpilot.retention;

import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.repo.ApplicationExecutionAuditRepository;
import ai.careerpilot.repo.RecommendationAuditRepository;
import ai.careerpilot.repo.ResumeTailoringAuditRepository;
import ai.careerpilot.repo.WorkflowCorrelationRepository;
import ai.careerpilot.repo.WorkflowDeadLetterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Additive, flag-gated data-retention for the append-only ledgers that grow unbounded: the workflow
 * dead-letter and correlation tables (Phase 3A) plus the recommendation / execution / resume-tailoring
 * audit trails. It is <b>disabled by default</b> ({@code retention.enabled=false}); with stock flags
 * {@link #purgeAll()} is a no-op and deletes nothing.
 *
 * <p>Each target has its own configurable retention window (in days); a row is eligible only when its
 * timestamp is strictly older than {@code now - days}. Correlations are additionally guarded to
 * <em>terminal</em> statuses only, so an in-flight workflow is never reaped. Every purge runs in its own
 * {@code REQUIRES_NEW} transaction and its own try/catch — one target failing never aborts the others
 * (the {@link TransactionTemplate} guarantees the boundary without relying on proxy self-invocation).
 * This is a pure delete-by-age maintenance job: no LLM, no events, no business logic.
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    /** Terminal correlation statuses eligible for purge — never STARTED/IN_PROGRESS. */
    private static final List<String> TERMINAL_CORRELATION_STATUSES = List.of(
            WorkflowCorrelation.STATUS_COMPLETED, WorkflowCorrelation.STATUS_FAILED,
            WorkflowCorrelation.STATUS_DEAD_LETTERED);

    private final WorkflowDeadLetterRepository deadLetters;
    private final WorkflowCorrelationRepository correlations;
    private final RecommendationAuditRepository recommendationAudits;
    private final ApplicationExecutionAuditRepository executionAudits;
    private final ResumeTailoringAuditRepository resumeTailoringAudits;
    private final TransactionTemplate tx;

    @Value("${retention.enabled:false}") private boolean enabled;
    @Value("${retention.workflow-dead-letter.days:90}") private int deadLetterDays;
    @Value("${retention.workflow-correlation.days:180}") private int correlationDays;
    @Value("${retention.recommendation-audit.days:365}") private int recommendationAuditDays;
    @Value("${retention.execution-audit.days:365}") private int executionAuditDays;
    @Value("${retention.resume-tailoring-audit.days:365}") private int resumeTailoringAuditDays;

    public RetentionService(WorkflowDeadLetterRepository deadLetters,
                            WorkflowCorrelationRepository correlations,
                            RecommendationAuditRepository recommendationAudits,
                            ApplicationExecutionAuditRepository executionAudits,
                            ResumeTailoringAuditRepository resumeTailoringAudits,
                            PlatformTransactionManager txManager) {
        this.deadLetters = deadLetters;
        this.correlations = correlations;
        this.recommendationAudits = recommendationAudits;
        this.executionAudits = executionAudits;
        this.resumeTailoringAudits = resumeTailoringAudits;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Purge every configured target. Returns a per-target map of rows deleted (or {@code -1} on a
     * per-target failure that was caught and logged). Empty map (nothing attempted) when disabled.
     */
    public Map<String, Long> purgeAll() {
        Map<String, Long> result = new LinkedHashMap<>();
        if (!enabled) {
            log.debug("Retention disabled; skipping purge");
            return result;
        }
        Instant now = Instant.now();
        result.put("workflow_dead_letter", safePurge("workflow_dead_letter",
                () -> deadLetters.deleteByCreatedAtBefore(now.minus(Duration.ofDays(deadLetterDays)))));
        result.put("workflow_correlation", safePurge("workflow_correlation",
                () -> correlations.deleteByStatusInAndUpdatedAtBefore(
                        TERMINAL_CORRELATION_STATUSES, now.minus(Duration.ofDays(correlationDays)))));
        result.put("recommendation_audit", safePurge("recommendation_audit",
                () -> recommendationAudits.deleteByCreatedAtBefore(now.minus(Duration.ofDays(recommendationAuditDays)))));
        result.put("execution_audit", safePurge("execution_audit",
                () -> executionAudits.deleteByCreatedAtBefore(now.minus(Duration.ofDays(executionAuditDays)))));
        result.put("resume_tailoring_audit", safePurge("resume_tailoring_audit",
                () -> resumeTailoringAudits.deleteByCreatedAtBefore(now.minus(Duration.ofDays(resumeTailoringAuditDays)))));
        log.info("Retention purge complete: {}", result);
        return result;
    }

    /** Each target purge is its own REQUIRES_NEW transaction + try/catch so one failure can't strand the others. */
    private long safePurge(String target, LongSupplier purge) {
        try {
            long deleted = tx.execute(status -> purge.getAsLong());
            if (deleted > 0) log.info("Retention purged {} rows from {}", deleted, target);
            return deleted;
        } catch (Exception e) {
            log.warn("Retention purge failed for {}: {}", target, e.toString());
            return -1;
        }
    }
}
