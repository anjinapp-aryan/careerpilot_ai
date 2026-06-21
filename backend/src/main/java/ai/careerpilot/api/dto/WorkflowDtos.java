package ai.careerpilot.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request/response shapes for AI Workflow. */
public final class WorkflowDtos {

    private WorkflowDtos() {}

    public record WorkflowAgent(
            String name,
            String status,
            String completedAt,
            String provider,
            Long durationMs) {}

    /** Response for workflow run operations (start, resume, get). */
    public record WorkflowRunResponse(
            UUID id,
            String threadId,
            String status,
            String targetRole,
            String targetSeniority,
            Integer resumeScore,
            Integer jobMatchScore,
            Integer atsScore,
            Integer interviewReadinessScore,
            Map<String, Object> state,
            List<WorkflowAgent> agents,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {}
}
