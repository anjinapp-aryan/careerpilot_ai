package ai.careerpilot.skillgap;

import java.util.List;
import java.util.Map;

/**
 * Phase 10 — the wire shape of {@code POST /skill-gap/runs}' response from the Python AI Execution
 * Plane (see {@code agent-service/app/skillgap/router.py}'s {@code SkillGapRunResponse}). Field
 * names are already camelCase on the Python side specifically so this record binds via Jackson's
 * default naming strategy with zero custom mapping. Java never interprets these fields' business
 * meaning (no AI reasoning in the Control Plane) — it only stores this verbatim as {@code
 * SkillGapAnalysis.resultJson} and returns it to callers.
 */
public record SkillGapAgentResponse(
        String missionId,
        String workflowId,
        String executionId,
        String status,
        int readinessScore,
        double confidence,
        List<Map<String, Object>> criticalSkillGaps,
        List<Map<String, Object>> importantSkillGaps,
        List<Map<String, Object>> optionalSkillGaps,
        List<Map<String, Object>> recommendedLearningRoadmap,
        int estimatedCompletionMonths,
        double missionProgress,
        List<String> strengths,
        List<String> risks,
        List<String> recommendations,
        List<String> errors) {
}
