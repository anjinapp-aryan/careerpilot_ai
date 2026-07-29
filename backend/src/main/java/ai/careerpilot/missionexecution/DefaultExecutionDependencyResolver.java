package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pre-Phase-9 Hardening — the only {@link ExecutionDependencyResolver}. Two static, deterministic
 * sources of blocking, matching the phase spec's own worked examples:
 * <ul>
 *   <li>{@link #PRECONDITIONS} — per-type metric gates (e.g. ATS/INTERVIEW require a resume score
 *       &gt;= 90), evaluated against {@link ExecutionContext#metrics()}.</li>
 *   <li>{@link #PREREQUISITE_WORKFLOWS} — per-type prerequisite workflows that must have reached
 *       {@link ExecutionState#COMPLETED} in {@link ExecutionContext#currentStates()}.</li>
 * </ul>
 * A type absent from either map has no precondition/prerequisite and is never blocked by this
 * resolver. This is intentionally a small, curated set (not all 15 {@link WorkflowType}s) —
 * matching the "never fabricate a signal" discipline used throughout this codebase.
 */
public class DefaultExecutionDependencyResolver implements ExecutionDependencyResolver {

    private static final Map<WorkflowType, List<Precondition>> PRECONDITIONS = Map.of(
            WorkflowType.ATS, List.of(new Precondition("Resume Score >= 90", "resume.score", PreconditionOperator.GTE, 90)),
            WorkflowType.INTERVIEW, List.of(new Precondition("Resume Score >= 90", "resume.score", PreconditionOperator.GTE, 90)),
            WorkflowType.JOB_DISCOVERY, List.of(new Precondition("LinkedIn Complete", "linkedin.complete", PreconditionOperator.IS_TRUE, 1)),
            WorkflowType.OFFER_EVALUATION, List.of(new Precondition("Interview Readiness >= 80", "interview.readiness", PreconditionOperator.GTE, 80)),
            WorkflowType.RELOCATION, List.of(new Precondition("Visa Documents Available", "visa.documentsAvailable", PreconditionOperator.IS_TRUE, 1)));

    private static final Map<WorkflowType, List<WorkflowType>> PREREQUISITE_WORKFLOWS = Map.of(
            WorkflowType.INTERVIEW, List.of(WorkflowType.RESUME, WorkflowType.ATS),
            WorkflowType.OFFER_EVALUATION, List.of(WorkflowType.INTERVIEW),
            WorkflowType.RELOCATION, List.of(WorkflowType.VISA));

    @Override
    public DependencyEvaluation evaluate(WorkflowType type, ExecutionContext context) {
        List<Precondition> unmet = PRECONDITIONS.getOrDefault(type, List.of()).stream()
                .filter(p -> !p.isMet(context.metrics()))
                .toList();

        List<WorkflowType> blockedBy = new ArrayList<>();
        for (WorkflowType prerequisite : PREREQUISITE_WORKFLOWS.getOrDefault(type, List.of())) {
            ExecutionState state = context.currentStates().get(prerequisite);
            if (state != ExecutionState.COMPLETED) {
                blockedBy.add(prerequisite);
            }
        }

        boolean blocked = !unmet.isEmpty() || !blockedBy.isEmpty();
        return new DependencyEvaluation(blocked, List.copyOf(blockedBy), unmet);
    }
}
