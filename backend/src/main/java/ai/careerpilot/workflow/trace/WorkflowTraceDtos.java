package ai.careerpilot.workflow.trace;

import java.time.Instant;
import java.util.List;

/**
 * Phase 3A follow-up — the read-model DTOs for the Workflow Correlation Trace API. All are immutable
 * records with plain, UI-renderable fields (no entities, no {@code JsonNode}) so the future timeline UI
 * can bind directly. Status values are plain strings matching the documented vocabularies:
 * workflow {@code RUNNING|COMPLETED|FAILED|PARTIAL}; step
 * {@code SUCCESS|FAILED|RUNNING|PENDING|NOT_STARTED|SKIPPED}.
 */
public final class WorkflowTraceDtos {

    private WorkflowTraceDtos() {}

    /** The full reconstructed workflow journey for one correlation id. */
    public record WorkflowTraceDto(
            String correlationId,
            String workflowType,
            String status,
            Instant startedAt,
            Instant completedAt,
            Long durationMs,
            List<WorkflowStepDto> steps,
            List<DeadLetterDto> deadLetters) {}

    /** One stage of the pipeline. {@code startedAt}/{@code completedAt}/{@code durationMs} are best-effort:
     * populated only from a real artifact timestamp, otherwise null (Phase 3A persists no per-step clock). */
    public record WorkflowStepDto(
            String step,
            String status,
            Instant startedAt,
            Instant completedAt,
            Long durationMs) {}

    /** A captured failure, projected from {@code workflow_dead_letter}. */
    public record DeadLetterDto(
            String worker,
            String stage,
            String error,
            Instant timestamp) {}

    /** Lightweight roll-up for list/badge rendering and support triage. */
    public record WorkflowSummaryDto(
            String correlationId,
            String workflowType,
            String status,
            int totalSteps,
            int completedSteps,
            int failedSteps,
            int deadLetterCount,
            Long durationMs) {}

    /** Correlation diagnostics — counts-oriented view for the {@code /diagnostics/correlation/{id}} endpoint. */
    public record CorrelationDiagnosticsDto(
            String correlationId,
            String workflowType,
            String status,
            int totalEvents,
            int completedStages,
            int failedStages,
            int deadLetters,
            Long workflowDurationMs) {}
}
