package ai.careerpilot.intent;

/**
 * Phase 11.1 — the intent taxonomy the {@link IntentEngine} classifies free text into.
 * Deliberately a separate, richer taxonomy from {@code ai.careerpilot.capability.CapabilityType}
 * (Phase 10.3) — per the Phase 11 target architecture, Intent Engine sits one layer above the
 * Capability Engine (Copilot → Intent Engine → Capability Planner → Capability Engine), and the
 * not-yet-built Capability Planner (Phase 11.2) is what will map an intent down to one or more
 * capabilities. {@code EXECUTIVE_COACH} has no Phase 10.3 capability equivalent yet — it exists
 * here because the phase spec's own examples require it ("I'm confused about my career" →
 * Executive Coach), anticipating a future capability.
 */
public enum IntentType {
    RESUME_ANALYSIS,
    CAREER_STRATEGY,
    INTERVIEW_PREPARATION,
    EXECUTIVE_COACH,
    GITHUB_ANALYSIS,
    JOB_RECOMMENDATION,
    LEARNING_HELP
}
