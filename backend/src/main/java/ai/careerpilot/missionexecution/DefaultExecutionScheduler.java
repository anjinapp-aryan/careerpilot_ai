package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pre-Phase-9 Hardening — the only {@link ExecutionScheduler}. Assigns week 1..N by walking
 * {@link #NATURAL_SEQUENCE} (exactly the phase spec's own worked example: Resume, LinkedIn,
 * Portfolio, Learning, Job Discovery, ATS, Interview, Offer Evaluation, Visa, Relocation) and
 * appending any other requested {@link WorkflowType} not in that sequence afterward, in the
 * order it was requested. Purely a slot assignment — {@link ExecutionDependencyResolver} is what
 * actually blocks a type regardless of its assigned week.
 */
public class DefaultExecutionScheduler implements ExecutionScheduler {

    private static final List<WorkflowType> NATURAL_SEQUENCE = List.of(
            WorkflowType.RESUME, WorkflowType.LINKEDIN, WorkflowType.PORTFOLIO, WorkflowType.LEARNING,
            WorkflowType.JOB_DISCOVERY, WorkflowType.ATS, WorkflowType.INTERVIEW,
            WorkflowType.OFFER_EVALUATION, WorkflowType.VISA, WorkflowType.RELOCATION);

    @Override
    public Map<WorkflowType, Integer> schedule(List<WorkflowType> requestedTypes) {
        Map<WorkflowType, Integer> weeks = new LinkedHashMap<>();
        int week = 1;
        for (WorkflowType type : NATURAL_SEQUENCE) {
            if (requestedTypes.contains(type)) {
                weeks.put(type, week++);
            }
        }
        for (WorkflowType type : requestedTypes) {
            if (!weeks.containsKey(type)) {
                weeks.put(type, week++);
            }
        }
        return weeks;
    }
}
