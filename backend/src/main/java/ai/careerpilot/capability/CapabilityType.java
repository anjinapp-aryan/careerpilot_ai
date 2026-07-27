package ai.careerpilot.capability;

/**
 * Phase 10.3 — the fixed set of AI capabilities the {@link CapabilityEngine} can route a
 * request to. Each maps to one or more MCP capabilities in {@link CapabilityRegistry}'s default
 * registrations. Not extensible via config in this phase — adding a capability means adding an
 * enum value plus a matching {@link CapabilityDefinition} and resolver rule, mirroring how a new
 * {@code CopilotSkill} is added (new enum value + handler + router wiring).
 */
public enum CapabilityType {
    RESUME_ANALYSIS,
    JOB_RECOMMENDATION,
    GITHUB_REVIEW,
    CAREER_STRATEGY,
    INTERVIEW_PREPARATION,
    LEARNING_HELP
}
