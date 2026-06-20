package ai.careerpilot.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Request/response shapes for AI Workflow. */
public final class WorkflowDtos {

    private WorkflowDtos() {}

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
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {}
}
