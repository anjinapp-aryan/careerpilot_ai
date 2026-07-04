package ai.careerpilot.workflow.trace;

/**
 * Phase 3A follow-up — the single source of truth for the ordered workflow stage catalog used by the
 * read-only trace projection. Each stage maps three already-existing Phase 3A identifiers together, so
 * the projection never guesses:
 *
 * <ul>
 *   <li>{@link #dtoStep} — the stable, UI-facing step name in the trace response;</li>
 *   <li>{@link #correlationStage} — the exact string a worker writes into
 *       {@code workflow_correlation.workflow_stage} via {@code WorkflowCorrelationService.advance(...)},
 *       which is how we know how far the engine progressed; and</li>
 *   <li>{@link #deadLetterWorkflow} / {@link #worker} — the {@code workflow_dead_letter.workflow} value
 *       a failing worker records, mapped back to its worker class name.</li>
 * </ul>
 *
 * The declaration order IS the pipeline order — {@code ordinal()} is the stage index the frontier
 * comparison relies on. This enum is read-only metadata; changing the workflow means adding a stage
 * here, nothing else in the trace layer.
 */
public enum WorkflowStage {

    APPLICATION_CREATED("APPLICATION_CREATED", "ENTRY", null, "WorkflowEntryBridge"),
    APPLICATION_TRACKED("APPLICATION_TRACKED", "TRACKING", "application-tracking", "ApplicationTrackingWorker"),
    STATUS_DETECTED("STATUS_DETECTED", "STATUS_DETECTION", "status-detection", "StatusDetectionWorker"),
    TIMELINE_UPDATED("TIMELINE_UPDATED", "TIMELINE", "application-timeline", "TimelineWorker"),
    EMAIL_CLASSIFIED("EMAIL_CLASSIFIED", "EMAIL_INTELLIGENCE", "email-intelligence", "EmailIntelligenceWorker"),
    INTERVIEW_DETECTED("INTERVIEW_DETECTED", "INTERVIEW_DETECTION", "interview-detection", "InterviewDetectionWorker"),
    INTERVIEW_TRACKED("INTERVIEW_TRACKED", "INTERVIEW_TRACKING", "interview-tracking", "InterviewTrackingWorker"),
    OFFER_DETECTION("OFFER_DETECTION", "OFFER_DETECTION", "offer-detection", "OfferDetectionWorker"),
    ANALYTICS_COMPUTED("ANALYTICS_COMPUTED", "ANALYTICS", "application-analytics", "AnalyticsWorker"),
    CAREER_INTELLIGENCE("CAREER_INTELLIGENCE", "CAREER_INTELLIGENCE", "career-intelligence", "CareerIntelligenceWorker");

    private final String dtoStep;
    private final String correlationStage;
    private final String deadLetterWorkflow;
    private final String worker;

    WorkflowStage(String dtoStep, String correlationStage, String deadLetterWorkflow, String worker) {
        this.dtoStep = dtoStep;
        this.correlationStage = correlationStage;
        this.deadLetterWorkflow = deadLetterWorkflow;
        this.worker = worker;
    }

    public String dtoStep() { return dtoStep; }
    public String correlationStage() { return correlationStage; }
    public String deadLetterWorkflow() { return deadLetterWorkflow; }
    public String worker() { return worker; }

    /**
     * Frontier index for a persisted {@code workflow_stage} value: the ordinal of the matching stage,
     * or {@code 0} (APPLICATION_CREATED) when the value is the initial {@code STARTED}/unknown marker.
     * The correlation is always minted at ENTRY, so index 0 is the safe floor.
     */
    public static int indexOfCorrelationStage(String correlationStage) {
        if (correlationStage != null) {
            for (WorkflowStage s : values()) {
                if (s.correlationStage.equalsIgnoreCase(correlationStage)) return s.ordinal();
            }
        }
        return 0;
    }

    /** Resolve the stage a dead-letter's {@code workflow} string belongs to, or {@code null} if unknown. */
    public static WorkflowStage forDeadLetterWorkflow(String workflow) {
        if (workflow == null) return null;
        for (WorkflowStage s : values()) {
            if (workflow.equalsIgnoreCase(s.deadLetterWorkflow)) return s;
        }
        return null;
    }
}
