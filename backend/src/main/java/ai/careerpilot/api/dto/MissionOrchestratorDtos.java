package ai.careerpilot.api.dto;

import ai.careerpilot.domain.MissionExecution;
import ai.careerpilot.mission.MissionOrchestratorService.Decision;
import ai.careerpilot.mission.MissionOrchestratorService.OrchestrationResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Mission Orchestrator, Phase 5 — response shape for {@code MissionOrchestratorController}. */
public final class MissionOrchestratorDtos {

    private MissionOrchestratorDtos() {}

    public record WorkflowDecisionResponse(String workflowId, String reason) {
        public static WorkflowDecisionResponse from(Decision d) {
            return new WorkflowDecisionResponse(d.workflowId(), d.reason());
        }
    }

    public record MissionExecutionResponse(
            UUID id, UUID missionId, String decisionSummary, Integer resumeScoreAtRun,
            Instant ranAt, List<WorkflowDecisionResponse> decisions
    ) {
        public static MissionExecutionResponse from(OrchestrationResult result) {
            MissionExecution e = result.execution();
            return new MissionExecutionResponse(e.getId(), e.getMissionId(), e.getDecisionSummary(),
                    e.getResumeScoreAtRun(), e.getRanAt(),
                    result.decisions().stream().map(WorkflowDecisionResponse::from).toList());
        }
    }
}
