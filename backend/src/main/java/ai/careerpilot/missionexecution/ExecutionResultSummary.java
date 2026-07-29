package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowType;

import java.time.Instant;

/**
 * Pre-Phase-9 Hardening — the ACTUAL outcome of a previously-run workflow, supplied by the
 * caller in {@link ExecutionContext#previousResults()}. This package never produces these itself
 * (it never executes anything); it only reads them to decide, e.g., whether an incomplete prior
 * attempt should be re-queued as {@link ExecutionPolicy#RETRY}.
 */
public record ExecutionResultSummary(WorkflowType workflowType, boolean completed, Double actualAtsScore,
                                      Double actualInterviewReadiness, Double actualLearningProgress,
                                      Double actualMissionProgressDelta, Double actualConfidence,
                                      Instant completedAt) {
}
