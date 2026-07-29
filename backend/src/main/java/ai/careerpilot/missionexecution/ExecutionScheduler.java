package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowType;

import java.util.List;
import java.util.Map;

/**
 * Pre-Phase-9 Hardening — assigns a week number (1-based execution slot) to each requested {@link
 * WorkflowType}, deterministically, from a fixed natural mission sequence (Resume → LinkedIn →
 * Portfolio → Learning → Job Discovery → ATS → Interview → Offer Evaluation → Visa → Relocation,
 * matching the phase spec's own worked example) with any other requested type appended after.
 */
public interface ExecutionScheduler {

    Map<WorkflowType, Integer> schedule(List<WorkflowType> requestedTypes);
}
