package ai.careerpilot.execution.timeline;

import ai.careerpilot.domain.ExecutionStageEvent;
import ai.careerpilot.repo.ExecutionStageEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * P5 — the single write seam for the execution stage timeline.
 *
 * <p><b>Observability must never be able to break the thing it observes.</b> Every method here is
 * total: no method throws, ever, for any reason. A database that is unreachable, a serialisation
 * failure, a null execution id — all degrade to a warn log and a no-op. This is not defensive
 * habit; it is the contract. Instrumentation is threaded through the one code path in this platform
 * that clicks submit on a live employer form, and a timeline write failing there must not be able
 * to change whether an application is sent, retried or recorded.
 *
 * <p><b>The recorder decides nothing.</b> It does not classify failures, choose stages, or infer
 * outcomes — callers pass an explicit {@link ExecutionStage} and, on failure, an explicit
 * {@link FailureCategory}. A recorder that guessed the category would produce exactly the confident
 * wrong attribution this taxonomy exists to prevent.
 *
 * <p>Gated by {@code execution.timeline.enabled} (default {@code false}). With the flag off every
 * method returns immediately without touching a repository, so an unmodified deployment is
 * byte-for-byte unchanged.
 */
@Service
public class ExecutionTimelineRecorder {

    private static final Logger log = LoggerFactory.getLogger(ExecutionTimelineRecorder.class);

    /** Detail blobs are diagnostic breadcrumbs, not a payload store. */
    private static final int MAX_DETAIL_CHARS = 4000;
    private static final int MAX_REASON_CHARS = 2000;

    private final ExecutionStageEventRepository events;
    private final ExecutionStageMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;

    public ExecutionTimelineRecorder(ExecutionStageEventRepository events,
                                     ExecutionStageMetrics metrics,
                                     @Value("${execution.timeline.enabled:false}") boolean enabled) {
        this.events = events;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Identifies the run being instrumented. Carried explicitly rather than resolved from a
     * ThreadLocal: the submit path already hands work between an event listener, a bounded executor
     * and a Tomcat thread, and thread-affinity is precisely the assumption that produced the
     * context-accounting defect this platform already fixed once.
     */
    public record RunContext(UUID executionId, UUID userId, UUID jobId) {
        public boolean usable() {
            return executionId != null && userId != null;
        }
    }

    /** Opens a stage. Returns the row id so the caller can close exactly the row it opened. */
    public UUID started(RunContext run, ExecutionStage stage) {
        return started(run, stage, null);
    }

    public UUID started(RunContext run, ExecutionStage stage, Map<String, Object> detail) {
        if (!enabled || run == null || !run.usable() || stage == null) return null;
        try {
            ExecutionStageEvent row = ExecutionStageEvent.builder()
                    .executionId(run.executionId())
                    .userId(run.userId())
                    .jobId(run.jobId())
                    .sequenceNo(nextSequence(run.executionId()))
                    .stage(stage.name())
                    .status(ExecutionStageEvent.STATUS_STARTED)
                    .startedAt(Instant.now())
                    .detail(serialise(detail))
                    .build();
            return events.save(row).getId();
        } catch (Exception e) {
            log.warn("EXECUTION_TIMELINE failed to open stage {} for execution {}: {}",
                    stage, run.executionId(), e.toString());
            return null;
        }
    }

    /** Closes a stage successfully. A null {@code eventId} is a no-op, not an error. */
    public void completed(UUID eventId, Map<String, Object> detail) {
        close(eventId, ExecutionStageEvent.STATUS_COMPLETED, null, null, detail);
    }

    public void completed(UUID eventId) {
        close(eventId, ExecutionStageEvent.STATUS_COMPLETED, null, null, null);
    }

    /** Closes a stage as failed, with an explicit category — never inferred here. */
    public void failed(UUID eventId, FailureCategory category, String reason) {
        close(eventId, ExecutionStageEvent.STATUS_FAILED, category, reason, null);
    }

    public void failed(UUID eventId, FailureCategory category, String reason, Map<String, Object> detail) {
        close(eventId, ExecutionStageEvent.STATUS_FAILED, category, reason, detail);
    }

    /** Genuinely not applicable to this run — never a disguised failure. */
    public void skipped(UUID eventId, String reason) {
        close(eventId, ExecutionStageEvent.STATUS_SKIPPED, null, reason, null);
    }

    /**
     * A stage with no meaningful duration — an instant that either happened or did not (approval
     * granted, claim won). Opened and closed in one write rather than leaving an open row that a
     * reader would have to interpret as "in flight".
     */
    public void mark(RunContext run, ExecutionStage stage, Map<String, Object> detail) {
        UUID id = started(run, stage, detail);
        if (id != null) completed(id, null);
    }

    private void close(UUID eventId, String status, FailureCategory category,
                       String reason, Map<String, Object> detail) {
        if (!enabled || eventId == null) return;
        try {
            Optional<ExecutionStageEvent> found = events.findById(eventId);
            if (found.isEmpty()) return;
            ExecutionStageEvent row = found.get();
            // Never rewrite a closed row: the first outcome recorded for a stage is the true one,
            // and a later overwrite would silently rewrite history.
            if (!ExecutionStageEvent.STATUS_STARTED.equals(row.getStatus())) return;

            Instant now = Instant.now();
            row.setStatus(status);
            row.setEndedAt(now);
            row.setDurationMs(Duration.between(row.getStartedAt(), now).toMillis());
            if (category != null) row.setFailureCategory(category.name());
            if (reason != null) row.setReason(truncate(reason, MAX_REASON_CHARS));
            if (detail != null) row.setDetail(serialise(detail));
            events.save(row);

            metrics.record(row.getStage(), status, row.getDurationMs(),
                    row.getFailureCategory());
        } catch (Exception e) {
            log.warn("EXECUTION_TIMELINE failed to close stage event {}: {}", eventId, e.toString());
        }
    }

    /**
     * Next sequence for this execution. A concurrent gap or duplicate here is harmless — ordering
     * is still correct and the read side sorts by it — so this deliberately avoids a lock. The
     * submit path is sequential by design (one browser, one lease), so contention is theoretical.
     */
    private int nextSequence(UUID executionId) {
        Integer max = events.maxSequenceNo(executionId);
        return max == null ? 1 : max + 1;
    }

    private String serialise(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) return null;
        try {
            return truncate(mapper.writeValueAsString(detail), MAX_DETAIL_CHARS);
        } catch (Exception e) {
            log.warn("EXECUTION_TIMELINE detail serialisation failed: {}", e.toString());
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
