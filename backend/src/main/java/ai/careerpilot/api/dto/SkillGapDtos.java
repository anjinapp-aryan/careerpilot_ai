package ai.careerpilot.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 10 — Skill Gap Intelligence Workflow REST DTOs. {@code result} is a plain
 * {@code Map<String, Object>} rather than a typed business object — same "DTO pattern for API
 * responses" convention this codebase already established for {@code WorkflowRunResponse}
 * (avoids Jackson type-definition errors on the JSON blob and keeps Java from needing typed
 * knowledge of the AI Execution Plane's business output shape).
 */
public final class SkillGapDtos {

    private SkillGapDtos() {}

    public record SkillGapAnalysisResponse(
            UUID id,
            UUID missionId,
            String workflowId,
            String executionId,
            String correlationId,
            String status,
            Map<String, Object> result,
            String errorMessage,
            Instant createdAt,
            Instant completedAt) {
    }
}
