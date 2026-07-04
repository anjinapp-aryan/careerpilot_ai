package ai.careerpilot.workflow.trace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3A follow-up — the stage catalog is the single source of truth for ordering and name mapping.
 * These lock the ordinal ordering (the frontier signal) and the correlation-stage / dead-letter-workflow
 * lookups the projection depends on.
 */
class WorkflowStageTest {

    @Test
    void declarationOrderIsPipelineOrder() {
        WorkflowStage[] v = WorkflowStage.values();
        assertThat(v[0]).isEqualTo(WorkflowStage.APPLICATION_CREATED);
        assertThat(v[v.length - 1]).isEqualTo(WorkflowStage.CAREER_INTELLIGENCE);
        assertThat(v).hasSize(10);
    }

    @Test
    void resolvesCorrelationStageToFrontierIndex() {
        assertThat(WorkflowStage.indexOfCorrelationStage("ENTRY")).isZero();
        assertThat(WorkflowStage.indexOfCorrelationStage("TIMELINE"))
                .isEqualTo(WorkflowStage.TIMELINE_UPDATED.ordinal());
        assertThat(WorkflowStage.indexOfCorrelationStage("CAREER_INTELLIGENCE"))
                .isEqualTo(WorkflowStage.CAREER_INTELLIGENCE.ordinal());
    }

    @Test
    void unknownOrNullCorrelationStageFloorsToZero() {
        assertThat(WorkflowStage.indexOfCorrelationStage(null)).isZero();
        assertThat(WorkflowStage.indexOfCorrelationStage("STARTED")).isZero();
        assertThat(WorkflowStage.indexOfCorrelationStage("nonsense")).isZero();
    }

    @Test
    void mapsDeadLetterWorkflowToStageAndWorker() {
        assertThat(WorkflowStage.forDeadLetterWorkflow("interview-detection"))
                .isEqualTo(WorkflowStage.INTERVIEW_DETECTED);
        assertThat(WorkflowStage.INTERVIEW_DETECTED.worker()).isEqualTo("InterviewDetectionWorker");
        assertThat(WorkflowStage.forDeadLetterWorkflow("unknown-worker")).isNull();
        assertThat(WorkflowStage.forDeadLetterWorkflow(null)).isNull();
    }
}
