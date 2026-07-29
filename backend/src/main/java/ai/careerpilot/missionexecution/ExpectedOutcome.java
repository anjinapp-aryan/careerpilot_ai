package ai.careerpilot.missionexecution;

/**
 * Pre-Phase-9 Hardening — what a workflow is expected to achieve if it runs, so a future phase
 * can compare Expected vs. {@link ExecutionResultSummary} (Actual) and trigger replanning. Fields
 * are nullable — most workflow types only have a meaningful expectation for one or two of them.
 */
public record ExpectedOutcome(Double expectedCompletionPercent, Double expectedAtsScore,
                               Double expectedInterviewReadiness, Double expectedLearningProgress,
                               Double expectedMissionProgressDelta, Double expectedConfidence) {
}
