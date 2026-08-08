package ai.careerpilot.execution.timeline;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ExecutionStageEvent;
import ai.careerpilot.execution.browser.BrowserSessionManager;
import ai.careerpilot.execution.browser.pool.BrowserLeasePool;
import ai.careerpilot.repo.ApplicationExecutionRepository;
import ai.careerpilot.repo.ExecutionStageEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * P5 — the read side: turns the append-only stage rows into the answer to
 * "exactly where did this application stop, and why".
 *
 * <p>Composes only what has already been recorded. It computes no stage, infers no outcome and
 * never calls the browser — a diagnostic that changes the system it diagnoses is not a diagnostic.
 * Every section is independently guarded, mirroring {@code CareerTimelineService} and
 * {@code BrowserHealthService}: one missing subsystem degrades that section to null rather than
 * failing the whole answer, because the person reading this is usually mid-incident.
 *
 * <p><b>Ownership is enforced</b> the same way as everywhere else in this codebase — a timeline for
 * an execution belonging to another user is indistinguishable from one that does not exist.
 */
@Service
public class ExecutionTimelineService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionTimelineService.class);

    private final ExecutionStageEventRepository events;
    private final ApplicationExecutionRepository executions;
    private final ExecutionStageMetrics metrics;
    private final BrowserSessionManager sessionManager;
    private final BrowserLeasePool leasePool;
    private final ExecutionTimelineRecorder recorder;

    public ExecutionTimelineService(ExecutionStageEventRepository events,
                                    ApplicationExecutionRepository executions,
                                    ExecutionStageMetrics metrics,
                                    BrowserSessionManager sessionManager,
                                    BrowserLeasePool leasePool,
                                    ExecutionTimelineRecorder recorder) {
        this.events = events;
        this.executions = executions;
        this.metrics = metrics;
        this.sessionManager = sessionManager;
        this.leasePool = leasePool;
        this.recorder = recorder;
    }

    /**
     * The full timeline for one execution the caller owns.
     *
     * <p>Returns empty only when the execution does not exist or belongs to someone else. An
     * execution that exists but has no recorded stages returns a populated envelope with an empty
     * stage list and an explicit note — "instrumentation is off" and "nothing happened" are
     * different facts and must not render identically.
     */
    public Optional<Map<String, Object>> timeline(UUID executionId, UUID userId) {
        ApplicationExecution exec = executions.findByIdAndUserId(executionId, userId).orElse(null);
        if (exec == null) return Optional.empty();

        List<ExecutionStageEvent> rows;
        try {
            rows = events.findByExecutionIdOrderBySequenceNoAsc(executionId);
        } catch (Exception e) {
            log.warn("EXECUTION_TIMELINE read failed for execution {}: {}", executionId, e.toString());
            rows = List.of();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executionId", executionId);
        out.put("jobId", exec.getJobId());
        out.put("executionStatus", exec.getExecutionStatus());
        out.put("checkpoint", exec.getCheckpoint());
        out.put("instrumentationEnabled", recorder.isEnabled());
        out.put("stages", renderStages(rows));
        out.put("stageDurations", durations(rows));
        out.put("totalDurationMs", totalDuration(rows));
        out.put("exit", exitSummary(exec, rows));
        out.put("answers", answerStatistics(rows));
        out.put("browser", browserSnapshot());
        if (rows.isEmpty()) {
            out.put("note", recorder.isEnabled()
                    ? "no stages recorded for this execution — it predates instrumentation or never started"
                    : "stage instrumentation is disabled (execution.timeline.enabled=false)");
        }
        return Optional.of(out);
    }

    private List<Map<String, Object>> renderStages(List<ExecutionStageEvent> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExecutionStageEvent row : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sequence", row.getSequenceNo());
            m.put("stage", row.getStage());
            m.put("displayName", displayNameOf(row.getStage()));
            m.put("status", row.getStatus());
            m.put("startedAt", row.getStartedAt() == null ? null : row.getStartedAt().toString());
            m.put("endedAt", row.getEndedAt() == null ? null : row.getEndedAt().toString());
            m.put("durationMs", row.getDurationMs());
            m.put("failureCategory", row.getFailureCategory());
            m.put("reason", row.getReason());
            m.put("detail", row.getDetail());
            out.add(m);
        }
        return out;
    }

    /** Per-stage durations, so "which component is slow" is answerable without arithmetic. */
    private Map<String, Long> durations(List<ExecutionStageEvent> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (ExecutionStageEvent row : rows) {
            if (row.getDurationMs() == null) continue;
            out.merge(row.getStage(), row.getDurationMs(), Long::sum);
        }
        return out;
    }

    /**
     * Wall-clock span of the run, from the first stage opening to the last one closing. Deliberately
     * not the sum of stage durations: stages do not tile the timeline and summing them would report
     * a total smaller than the elapsed time, which reads as a measurement error.
     */
    private Long totalDuration(List<ExecutionStageEvent> rows) {
        if (rows.isEmpty()) return null;
        Instant first = rows.get(0).getStartedAt();
        Instant last = null;
        for (ExecutionStageEvent row : rows) {
            Instant end = row.getEndedAt() != null ? row.getEndedAt() : row.getStartedAt();
            if (end != null && (last == null || end.isAfter(last))) last = end;
        }
        if (first == null || last == null) return null;
        return Duration.between(first, last).toMillis();
    }

    /**
     * The headline: which stage was the last one, whether it ended, and why it was the last.
     *
     * <p>An open (STARTED, never closed) final row is reported as {@code IN_FLIGHT} rather than as
     * a failure. A run killed mid-stage and a run still working look identical in the data, so this
     * says which stage it was inside and does not claim to know which of the two happened.
     */
    private Map<String, Object> exitSummary(ApplicationExecution exec, List<ExecutionStageEvent> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            out.put("stoppedAt", null);
            out.put("outcome", "NO_STAGES_RECORDED");
            out.put("reason", null);
            out.put("failureCategory", null);
            out.put("recoveryAction", exec.getExecutionStatus());
            return out;
        }
        ExecutionStageEvent last = rows.get(rows.size() - 1);
        // A failed stage is the interesting one even if a later bookkeeping stage closed cleanly.
        ExecutionStageEvent failure = rows.stream()
                .filter(r -> ExecutionStageEvent.STATUS_FAILED.equals(r.getStatus()))
                .reduce((a, b) -> b)
                .orElse(null);
        ExecutionStageEvent subject = failure != null ? failure : last;

        out.put("stoppedAt", subject.getStage());
        out.put("stoppedAtDisplayName", displayNameOf(subject.getStage()));
        out.put("outcome", ExecutionStageEvent.STATUS_STARTED.equals(last.getStatus())
                ? "IN_FLIGHT" : subject.getStatus());
        out.put("reason", subject.getReason() != null ? subject.getReason() : exec.getFailureReason());
        out.put("failureCategory", subject.getFailureCategory());
        // The recovery decision already lives on the execution row — surfaced here rather than
        // recomputed, so the timeline and the recovery centre can never disagree.
        out.put("recoveryAction", exec.getExecutionStatus());
        out.put("retryOfExecutionId", exec.getRetryOfExecutionId());
        return out;
    }

    /**
     * Question/answer statistics for the run, read back from the detail recorded at the resolution
     * stages. Absent when those stages did not run — never zero-filled, because "no questions were
     * resolved" and "we never got that far" are different and only one of them is a problem.
     */
    private Map<String, Object> answerStatistics(List<ExecutionStageEvent> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            ExecutionStageEvent row = rows.get(i);
            if (ExecutionStage.ANSWER_CONFIDENCE_COMPUTED.name().equals(row.getStage())
                    || ExecutionStage.QUESTIONS_RESOLVED.name().equals(row.getStage())) {
                if (row.getDetail() != null) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("stage", row.getStage());
                    out.put("detail", row.getDetail());
                    return out;
                }
            }
        }
        return null;
    }

    /**
     * Lightweight browser metadata only — uptime, contexts served, lease occupancy. Deliberately no
     * page URL or title: those are read from a live page, and this endpoint must never touch the
     * browser, let alone one that may since have been recycled.
     */
    private Map<String, Object> browserSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Instant launchedAt = sessionManager.launchedAt();
            out.put("launched", sessionManager.isLaunched());
            out.put("launchedAt", launchedAt == null ? null : launchedAt.toString());
            out.put("uptimeSeconds", launchedAt == null
                    ? null : Duration.between(launchedAt, Instant.now()).toSeconds());
            out.put("contextsServed", sessionManager.contextsSinceLaunch());
            out.put("openContexts", sessionManager.openContexts());
            out.put("activeLeases", leasePool.activeLeases());
            out.put("maxLeases", leasePool.maxLeases());
            out.put("note", "current browser state, not the state during this execution — "
                    + "the browser may have been recycled since");
        } catch (Exception e) {
            log.warn("EXECUTION_TIMELINE browser snapshot failed: {}", e.toString());
            return null;
        }
        return out;
    }

    /** Aggregate stage statistics for the Operations Center. Process-lifetime counters. */
    public Map<String, Object> stageMetrics() {
        return metrics.snapshot();
    }

    private static String displayNameOf(String stage) {
        try {
            return ExecutionStage.valueOf(stage).displayName();
        } catch (Exception e) {
            // A stage name from an older build that this one no longer defines. Shown raw rather
            // than dropped: an unrecognised stage is still evidence of what happened.
            return stage;
        }
    }
}
