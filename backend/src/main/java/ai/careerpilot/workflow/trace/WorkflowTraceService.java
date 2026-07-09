package ai.careerpilot.workflow.trace;

import ai.careerpilot.domain.ApplicationAnalytics;
import ai.careerpilot.domain.ApplicationLifecycle;
import ai.careerpilot.domain.ApplicationTimeline;
import ai.careerpilot.domain.CareerIntelligence;
import ai.careerpilot.domain.Interview;
import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.domain.WorkflowDeadLetter;
import ai.careerpilot.repo.ApplicationAnalyticsRepository;
import ai.careerpilot.repo.ApplicationLifecycleRepository;
import ai.careerpilot.repo.ApplicationTimelineRepository;
import ai.careerpilot.repo.CareerIntelligenceRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.WorkflowCorrelationRepository;
import ai.careerpilot.repo.WorkflowDeadLetterRepository;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.CorrelationDiagnosticsDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.DeadLetterDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.GraphEdgeDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.GraphNodeDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.WorkflowEventDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.WorkflowGraphDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.WorkflowStepDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.WorkflowSummaryDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.WorkflowTraceDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 3A follow-up — a pure, read-only projection that reconstructs a whole workflow journey from the
 * existing Phase 3A tables for a single {@code correlationId}. It is a read-model only: no persistence,
 * no caching, no LLM, no event publishing, no engine coupling. Deterministic — the same rows always
 * yield the same trace.
 *
 * <p><b>How far the engine got</b> is read from {@code workflow_correlation.workflow_stage} (the
 * frontier) and {@code .status}; <b>what failed</b> is read from {@code workflow_dead_letter};
 * <b>artifact timestamps</b> are best-effort enrichment from the lifecycle/timeline/interview/analytics/
 * career rows (scoped by the correlation's {@code userId}+{@code jobId}). Missing stages are never
 * inferred — absence maps to {@code NOT_STARTED}, a dead-letter maps to {@code FAILED}.
 *
 * <p><b>Multi-tenant:</b> every lookup is scoped to the caller's {@code userId}; a correlation owned by
 * another user is reported as not-found (404, never 403) so existence never leaks.
 */
@Service
public class WorkflowTraceService {

    // workflow-level status vocabulary
    static final String WF_COMPLETED = "COMPLETED";
    static final String WF_FAILED = "FAILED";
    static final String WF_PARTIAL = "PARTIAL";
    static final String WF_RUNNING = "RUNNING";

    // step-level status vocabulary
    static final String STEP_SUCCESS = "SUCCESS";
    static final String STEP_FAILED = "FAILED";
    static final String STEP_RUNNING = "RUNNING";
    static final String STEP_PENDING = "PENDING";
    static final String STEP_NOT_STARTED = "NOT_STARTED";
    static final String STEP_SKIPPED = "SKIPPED";

    /** Lifecycle statuses at which OfferDetectionWorker legitimately emits a terminal outcome. */
    private static final Set<String> OFFER_OUTCOME_STATUSES = Set.of(
            ApplicationLifecycle.STATUS_OFFER_RECEIVED, ApplicationLifecycle.STATUS_NEGOTIATION,
            ApplicationLifecycle.STATUS_ACCEPTED, ApplicationLifecycle.STATUS_REJECTED);

    private final WorkflowCorrelationRepository correlations;
    private final WorkflowDeadLetterRepository deadLetters;
    private final ApplicationLifecycleRepository lifecycles;
    private final ApplicationTimelineRepository timelines;
    private final InterviewRepository interviews;
    private final ApplicationAnalyticsRepository analytics;
    private final CareerIntelligenceRepository career;

    public WorkflowTraceService(WorkflowCorrelationRepository correlations,
                                WorkflowDeadLetterRepository deadLetters,
                                ApplicationLifecycleRepository lifecycles,
                                ApplicationTimelineRepository timelines,
                                InterviewRepository interviews,
                                ApplicationAnalyticsRepository analytics,
                                CareerIntelligenceRepository career) {
        this.correlations = correlations;
        this.deadLetters = deadLetters;
        this.lifecycles = lifecycles;
        this.timelines = timelines;
        this.interviews = interviews;
        this.analytics = analytics;
        this.career = career;
    }

    /** Full reconstructed trace for a correlation id, scoped to {@code userId}. 404 (NoSuchElement) if
     * absent or owned by another user; 400 (IllegalArgument) if the id is not a UUID. */
    @Transactional(readOnly = true)
    public WorkflowTraceDto getWorkflow(String correlationId, UUID userId) {
        UUID cid = parse(correlationId);
        WorkflowCorrelation corr = correlations.findByCorrelationId(cid)
                .filter(c -> c.getUserId() != null && c.getUserId().equals(userId))
                .orElseThrow(() -> new NoSuchElementException("workflow correlation not found"));
        return build(corr);
    }

    /** Lightweight roll-up derived from the same reconstruction. */
    @Transactional(readOnly = true)
    public WorkflowSummaryDto summarize(String correlationId, UUID userId) {
        WorkflowTraceDto t = getWorkflow(correlationId, userId);
        int completed = (int) t.steps().stream().filter(s -> STEP_SUCCESS.equals(s.status())).count();
        int failed = (int) t.steps().stream().filter(s -> STEP_FAILED.equals(s.status())).count();
        return new WorkflowSummaryDto(t.correlationId(), t.workflowType(), t.status(),
                t.steps().size(), completed, failed, t.deadLetters().size(), t.durationMs());
    }

    /** Counts-oriented diagnostics view for the same reconstruction. */
    @Transactional(readOnly = true)
    public CorrelationDiagnosticsDto diagnostics(String correlationId, UUID userId) {
        WorkflowTraceDto t = getWorkflow(correlationId, userId);
        int completed = (int) t.steps().stream().filter(s -> STEP_SUCCESS.equals(s.status())).count();
        int failed = (int) t.steps().stream().filter(s -> STEP_FAILED.equals(s.status())).count();
        return new CorrelationDiagnosticsDto(t.correlationId(), t.workflowType(), t.status(),
                completed + failed, completed, failed, t.deadLetters().size(), t.durationMs());
    }

    /** Graph projection ({@code nodes[] / edges[]}) of the same reconstruction, for workflow visualization. */
    @Transactional(readOnly = true)
    public WorkflowGraphDto graph(String correlationId, UUID userId) {
        WorkflowTraceDto t = getWorkflow(correlationId, userId);
        List<GraphNodeDto> nodes = new ArrayList<>();
        List<GraphEdgeDto> edges = new ArrayList<>();
        String prev = null;
        for (WorkflowStepDto s : t.steps()) {
            nodes.add(new GraphNodeDto(s.step(), humanize(s.step()), s.status(), s.completedAt()));
            if (prev != null) edges.add(new GraphEdgeDto(prev, s.step()));
            prev = s.step();
        }
        return new WorkflowGraphDto(t.correlationId(), t.status(), nodes, edges);
    }

    /** Ordered raw-event projection for support/debugging — one event per stage that actually occurred
     * (NOT_STARTED/PENDING omitted). Deterministic ids so the same trace always yields the same events. */
    @Transactional(readOnly = true)
    public List<WorkflowEventDto> events(String correlationId, UUID userId) {
        WorkflowTraceDto t = getWorkflow(correlationId, userId);
        List<WorkflowEventDto> out = new ArrayList<>();
        for (WorkflowStepDto s : t.steps()) {
            if (STEP_NOT_STARTED.equals(s.status()) || STEP_PENDING.equals(s.status())) continue;
            Instant ts = s.completedAt() != null ? s.completedAt() : s.startedAt();
            out.add(new WorkflowEventDto(eventId(t.correlationId(), s.step()),
                    t.correlationId(), s.step(), ts, s.status()));
        }
        return out;
    }

    /** Deterministic, stable event id for a (correlation, stage) pair — no event-log table exists. */
    private static String eventId(String correlationId, String step) {
        return UUID.nameUUIDFromBytes((correlationId + ":" + step).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
    }

    /** {@code APPLICATION_TRACKED} -> {@code Application Tracked} (display label for graph nodes). */
    private static String humanize(String step) {
        if (step == null || step.isBlank()) return step;
        StringBuilder b = new StringBuilder();
        for (String w : step.split("_")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
        }
        return b.toString();
    }

    // ── reconstruction ──

    private WorkflowTraceDto build(WorkflowCorrelation corr) {
        UUID cid = corr.getCorrelationId();
        List<WorkflowDeadLetter> dl = deadLetters.findByCorrelationIdOrderByCreatedAtDesc(cid);

        // Map each captured failure to its stage (keeping the newest per stage for the timestamp).
        Map<WorkflowStage, WorkflowDeadLetter> dlByStage = new EnumMap<>(WorkflowStage.class);
        List<DeadLetterDto> deadLetterDtos = new ArrayList<>();
        for (WorkflowDeadLetter d : dl) {
            WorkflowStage stage = WorkflowStage.forDeadLetterWorkflow(d.getWorkflow());
            if (stage != null) dlByStage.putIfAbsent(stage, d);
            deadLetterDtos.add(new DeadLetterDto(
                    stage != null ? stage.worker() : d.getWorkflow(),
                    d.getStage(), d.getException(), d.getCreatedAt()));
        }

        int frontier = WorkflowStage.indexOfCorrelationStage(corr.getWorkflowStage());
        String workflowStatus = deriveWorkflowStatus(corr, dl.isEmpty());

        // Artifact timestamps (best-effort, scoped to this correlation's user+job).
        Instant lifecycleAt = null; String lifecycleStatus = null;
        if (corr.getJobId() != null) {
            ApplicationLifecycle lc = lifecycles.findByUserIdAndJobId(corr.getUserId(), corr.getJobId()).orElse(null);
            if (lc != null) { lifecycleAt = lc.getCreatedAt(); lifecycleStatus = lc.getCurrentStatus(); }
        }
        Instant timelineAt = corr.getJobId() == null ? null
                : first(timelines.findByUserIdAndJobIdOrderByOccurredAtDesc(corr.getUserId(), corr.getJobId()),
                        ApplicationTimeline::getOccurredAt);
        Instant interviewAt = corr.getJobId() == null ? null
                : first(interviews.findByUserIdAndJobIdOrderByCreatedAtDesc(corr.getUserId(), corr.getJobId()),
                        Interview::getCreatedAt);
        Instant analyticsAt = first(analytics.findByUserIdOrderByComputedAtDesc(corr.getUserId()),
                ApplicationAnalytics::getComputedAt);
        Instant careerAt = first(career.findByUserIdOrderByComputedAtDesc(corr.getUserId()),
                CareerIntelligence::getComputedAt);
        boolean hasOfferOutcome = lifecycleStatus != null && OFFER_OUTCOME_STATUSES.contains(lifecycleStatus);

        List<WorkflowStepDto> steps = new ArrayList<>();
        for (WorkflowStage stage : WorkflowStage.values()) {
            String status = resolveStepStatus(stage, frontier, corr.getStatus(), dlByStage.keySet(), hasOfferOutcome);
            Instant completedAt = completedAtFor(stage, status, corr, dlByStage,
                    lifecycleAt, timelineAt, interviewAt, analyticsAt, careerAt);
            Instant startedAt = stage == WorkflowStage.APPLICATION_CREATED ? corr.getCreatedAt() : null;
            Long durationMs = (startedAt != null && completedAt != null)
                    ? Duration.between(startedAt, completedAt).toMillis() : null;
            steps.add(new WorkflowStepDto(stage.dtoStep(), status, startedAt, completedAt, durationMs));
        }

        boolean terminal = WF_COMPLETED.equals(workflowStatus) || WF_FAILED.equals(workflowStatus)
                || WF_PARTIAL.equals(workflowStatus);
        Instant startedAt = corr.getCreatedAt();
        Instant completedAt = terminal ? corr.getUpdatedAt() : null;
        Long durationMs = (startedAt != null && completedAt != null)
                ? Duration.between(startedAt, completedAt).toMillis() : null;

        return new WorkflowTraceDto(cid.toString(), normalizeType(corr.getWorkflowType()), workflowStatus,
                startedAt, completedAt, durationMs, steps, deadLetterDtos);
    }

    /**
     * Deterministic per-step status. Ordering signal is the correlation frontier (last stage the engine
     * advanced to); overlays are the dead-letter set and the offer-branch skip. Never infers an artifact
     * it cannot see.
     */
    private String resolveStepStatus(WorkflowStage stage, int frontier, String corrStatus,
                                     Set<WorkflowStage> failedStages, boolean hasOfferOutcome) {
        if (failedStages.contains(stage)) return STEP_FAILED;

        int idx = stage.ordinal();
        boolean completed = WorkflowCorrelation.STATUS_COMPLETED.equals(corrStatus);
        boolean corrFailed = WorkflowCorrelation.STATUS_FAILED.equals(corrStatus)
                || WorkflowCorrelation.STATUS_DEAD_LETTERED.equals(corrStatus);

        // Offer detection is a conditional branch: if the engine advanced past it but the lifecycle never
        // reached an offer/accept/reject outcome, the worker legitimately emitted nothing — it was skipped,
        // not failed and not a fabricated success.
        if (stage == WorkflowStage.OFFER_DETECTION && idx < frontier && !hasOfferOutcome) {
            return STEP_SKIPPED;
        }

        if (idx < frontier) return STEP_SUCCESS;
        if (idx == frontier) {
            if (completed) return STEP_SUCCESS;
            if (corrFailed) return STEP_FAILED;
            return STEP_SUCCESS; // the frontier stage ran (it advanced only after doing its work)
        }
        // idx > frontier
        if (completed) return STEP_SUCCESS;   // defensive; frontier is the last stage on completion
        if (corrFailed) return STEP_NOT_STARTED;
        if (idx == frontier + 1) return STEP_PENDING; // the next awaited stage
        return STEP_NOT_STARTED;
    }

    private Instant completedAtFor(WorkflowStage stage, String status, WorkflowCorrelation corr,
                                   Map<WorkflowStage, WorkflowDeadLetter> dlByStage,
                                   Instant lifecycleAt, Instant timelineAt, Instant interviewAt,
                                   Instant analyticsAt, Instant careerAt) {
        if (STEP_FAILED.equals(status)) {
            WorkflowDeadLetter d = dlByStage.get(stage);
            return d != null ? d.getCreatedAt() : null;
        }
        if (!STEP_SUCCESS.equals(status)) return null; // PENDING/NOT_STARTED/RUNNING/SKIPPED have no completion
        return switch (stage) {
            case APPLICATION_CREATED -> corr.getCreatedAt();
            case APPLICATION_TRACKED, STATUS_DETECTED -> lifecycleAt;
            case TIMELINE_UPDATED -> timelineAt;
            case INTERVIEW_TRACKED -> interviewAt;
            case ANALYTICS_COMPUTED -> analyticsAt;
            case CAREER_INTELLIGENCE -> careerAt;
            // EMAIL_CLASSIFIED, INTERVIEW_DETECTED, OFFER_DETECTION persist no dedicated artifact clock.
            default -> null;
        };
    }

    private String deriveWorkflowStatus(WorkflowCorrelation corr, boolean noDeadLetters) {
        String s = corr.getStatus();
        if (WorkflowCorrelation.STATUS_COMPLETED.equals(s)) {
            return noDeadLetters ? WF_COMPLETED : WF_PARTIAL; // finished, but some stage was dead-lettered
        }
        if (WorkflowCorrelation.STATUS_FAILED.equals(s) || WorkflowCorrelation.STATUS_DEAD_LETTERED.equals(s)) {
            return WF_FAILED;
        }
        // STARTED / IN_PROGRESS
        return noDeadLetters ? WF_RUNNING : WF_FAILED; // a dead-letter on a still-running workflow halted a branch
    }

    private static <T> Instant first(List<T> rows, java.util.function.Function<T, Instant> ts) {
        return (rows == null || rows.isEmpty()) ? null : ts.apply(rows.get(0));
    }

    /** e.g. {@code application-tracking} -> {@code APPLICATION_TRACKING}; null-safe. */
    private static String normalizeType(String type) {
        return type == null ? null : type.trim().replace('-', '_').replace(' ', '_').toUpperCase();
    }

    private static UUID parse(String correlationId) {
        try {
            return UUID.fromString(correlationId.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("correlationId must be a valid UUID");
        }
    }
}
