package ai.careerpilot.api.dto;

import java.util.List;

/**
 * Response for {@code POST /api/jobs/{id}/explain} — the "Why am I a match?" action.
 * Served from cache after the first call; the only LLM-backed surface in the job engine.
 */
public record JobMatchExplanationDto(
        List<String> matchingSkills,
        List<String> missingSkills,
        List<String> resumeImprovements,
        List<String> atsImprovements,
        String modelUsed) {}
