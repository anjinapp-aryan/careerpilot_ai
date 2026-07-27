package ai.careerpilot.career.agent;

import java.util.UUID;

/**
 * Phase 11.6 — the only {@link AgentTaskExecutor} implementation shipped in this phase, and
 * deliberately a no-op: it never calls any real business service. Every one of the 8 {@link
 * AgentTaskType}s already has a real, existing engine in this codebase (job discovery →
 * {@code JobDiscoveryScheduler}/{@code DailyJobDiscoveryScheduler}, resume optimization → the
 * Phase 2D.2 ATS pipeline, application tracking → Phase 3A workflow tracking, interview planning
 * → {@code InterviewService}/{@code StarStoryEngine}, career roadmap update → {@code
 * CareerRoadmapGeneratorService}, learning roadmap / skill gap detection → {@code
 * SkillGapIntelligenceService}, salary trend monitoring → {@code OfferAnalysisService}) — per the
 * Phase 11 mission's explicit "DO NOT replace any existing subsystem," this phase does not wire
 * autonomous dispatch into any of them. {@link #execute} always returns {@link
 * TaskOutcome#DEFERRED} with an explanatory detail, so the observe → plan → reflect loop is
 * genuinely exercised end-to-end without ever triggering a real side-effecting action.
 *
 * <p>A future phase that wants real dispatch would implement {@link AgentTaskExecutor} against
 * the specific engine for each {@link AgentTaskType} and swap it in via {@link
 * AutonomousCareerAgentConfig} — this class and interface are the seam, not the final word.
 */
public class DeferredAgentTaskExecutor implements AgentTaskExecutor {

    @Override
    public AgentTaskResult execute(AgentTaskType type, UUID userId) {
        return new AgentTaskResult(type, TaskOutcome.DEFERRED,
                "Autonomous dispatch for " + type + " is not yet connected to any business service (Phase 11.6 scope)");
    }
}
