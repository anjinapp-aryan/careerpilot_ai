package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityType;

import java.util.Map;

/** Phase 11.3 — merges every capability's {@link ExecutionResult} into one {@link MergedExecutionContext}. */
public interface ResultMerger {

    MergedExecutionContext merge(Map<CapabilityType, ExecutionResult> results);
}
