package ai.careerpilot.capability;

/**
 * Phase 10.3 — the default {@link CapabilityResolver}: simple keyword matching, styled after
 * {@code ai.careerpilot.service.copilot.CopilotSkillRouter#inferSkillFromMessage} (same
 * lower-case-and-contains approach, same "can be upgraded to NLP intent detection later" scope).
 * Order matters — more specific checks run first so they win over broader ones.
 */
public class KeywordCapabilityResolver implements CapabilityResolver {

    @Override
    public CapabilityType resolve(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String lower = message.toLowerCase();

        if (lower.contains("github") || lower.contains("repo") || lower.contains("repository") || lower.contains("portfolio")) {
            return CapabilityType.GITHUB_REVIEW;
        }
        if (lower.contains("resume") || lower.contains("cv")) {
            return CapabilityType.RESUME_ANALYSIS;
        }
        if (lower.contains("interview")) {
            return CapabilityType.INTERVIEW_PREPARATION;
        }
        if (lower.contains("career strategy") || lower.contains("career plan") || lower.contains("career path")
                || lower.contains("success probability")) {
            return CapabilityType.CAREER_STRATEGY;
        }
        if (lower.contains("job recommend") || lower.contains("recommend a job") || lower.contains("job match")) {
            return CapabilityType.JOB_RECOMMENDATION;
        }
        if (lower.contains("documentation") || lower.contains("how do i use") || lower.contains("explain")
                || lower.contains("spring ai") || lower.contains("spring boot") || lower.contains("langgraph")) {
            return CapabilityType.LEARNING_HELP;
        }
        return null;
    }
}
