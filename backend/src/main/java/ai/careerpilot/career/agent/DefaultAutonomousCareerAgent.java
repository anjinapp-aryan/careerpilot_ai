package ai.careerpilot.career.agent;

import ai.careerpilot.career.monitor.CareerInsights;
import ai.careerpilot.career.monitor.CareerMonitor;
import ai.careerpilot.mission.MissionAwareDailyBriefService;
import ai.careerpilot.mission.MissionAwareDailyBriefService.MissionBrief;
import ai.careerpilot.mission.WorkflowPlanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 11.6 — the default {@link AutonomousCareerAgent}. Full loop per call: eligibility check
 * ({@link TaskScheduler}) → observe (via Phase 11.5's {@link CareerMonitor}, if its own
 * independent {@code career.monitor.enabled} flag is on — an empty observation otherwise, never
 * an error) → plan ({@link AgentPlanner}) → dispatch each planned task ({@link
 * AgentTaskExecutor} — {@link DeferredAgentTaskExecutor} by default, so nothing real actually
 * happens yet) → reflect → remember ({@link AgentMemory}).
 *
 * <p>{@code CareerMonitor} is injected via {@link ObjectProvider} and every call to it is
 * wrapped in try/catch — this agent must never fail a run just because the (also dark-by-default)
 * proactive-intelligence layer beneath it is unavailable or throws.
 *
 * <p><b>Phase 7A — Mission-Aware Autonomous Career Agent.</b> This class remains the single
 * {@link AutonomousCareerAgent} implementation (no {@code AgentV2}, no {@code MissionAgent}) and
 * the single planning seam ({@link AgentPlanner}, unchanged interface) — Mission-awareness is
 * added as one more optional observation source, exactly the same shape {@link CareerMonitor}
 * already has. {@code missionBriefProvider} ({@code ObjectProvider<MissionAwareDailyBriefService>})
 * is consulted only when {@code career.mission.agent.enabled} is on; it reuses Phase 6A's already-
 * composed {@link MissionAwareDailyBriefService} — the same facade the Daily Coach reads — so this
 * agent owns zero Mission/Strategy business rules of its own; it only translates that brief's
 * {@code priorityWorkflows()} (the Mission Orchestrator's own decision list) into an {@link
 * AgentTaskType} via {@link DefaultAgentPlanner}. The agent never decides which country, salary,
 * or strategy to pursue — it only asks "what should I execute next," and Mission-awareness only
 * changes where that question's answer comes from.
 *
 * <p>{@code workflowPlannerProvider} ({@code ObjectProvider<WorkflowPlanner>}, reusing Phase
 * 6A.1's extension-point interface rather than introducing a second one) is held as a forward-
 * ready seam for a future richer Workflow Planner to participate in this agent's planning —
 * provisioned but never called in this phase, matching this codebase's established
 * foundation-only discipline (e.g. {@code ai.springai} Phase 9.1, {@code ai.careerpilot.mcp}
 * Phase 10.1). No bean implements it today, so it changes nothing.
 */
public class DefaultAutonomousCareerAgent implements AutonomousCareerAgent {

    private static final Logger log = LoggerFactory.getLogger(DefaultAutonomousCareerAgent.class);

    private final ObjectProvider<CareerMonitor> careerMonitorProvider;
    private final AgentPlanner planner;
    private final AgentTaskExecutor executor;
    private final TaskScheduler scheduler;
    private final AgentMemory memory;
    private final AgentMetrics metrics;
    private final Duration minRunInterval;
    private final ObjectProvider<MissionAwareDailyBriefService> missionBriefProvider;
    private final ObjectProvider<WorkflowPlanner> workflowPlannerProvider;
    private final boolean missionAwareEnabled;

    /** Pre-7A signature, preserved so every existing call site keeps compiling unchanged. */
    public DefaultAutonomousCareerAgent(ObjectProvider<CareerMonitor> careerMonitorProvider, AgentPlanner planner,
                                         AgentTaskExecutor executor, TaskScheduler scheduler, AgentMemory memory,
                                         AgentMetrics metrics, Duration minRunInterval) {
        this(careerMonitorProvider, planner, executor, scheduler, memory, metrics, minRunInterval, null, null, false);
    }

    public DefaultAutonomousCareerAgent(ObjectProvider<CareerMonitor> careerMonitorProvider, AgentPlanner planner,
                                         AgentTaskExecutor executor, TaskScheduler scheduler, AgentMemory memory,
                                         AgentMetrics metrics, Duration minRunInterval,
                                         ObjectProvider<MissionAwareDailyBriefService> missionBriefProvider,
                                         ObjectProvider<WorkflowPlanner> workflowPlannerProvider,
                                         boolean missionAwareEnabled) {
        this.careerMonitorProvider = careerMonitorProvider;
        this.planner = planner;
        this.executor = executor;
        this.scheduler = scheduler;
        this.memory = memory;
        this.metrics = metrics;
        this.minRunInterval = minRunInterval;
        this.missionBriefProvider = missionBriefProvider;
        this.workflowPlannerProvider = workflowPlannerProvider;
        this.missionAwareEnabled = missionAwareEnabled;
    }

    @Override
    public AgentReflection runOnce(UUID userId) {
        if (!scheduler.isEligibleToRun(userId, minRunInterval)) {
            metrics.recordSkippedRun("cooldown");
            return AgentReflection.skipped(userId, "skipped: ran within the last " + minRunInterval);
        }

        long start = System.currentTimeMillis();
        AgentObservation observation = observe(userId);
        AgentExecutionPlan plan = planner.plan(observation);

        List<AgentTaskResult> results = plan.tasks().stream()
                .map(type -> {
                    AgentTaskResult result = executor.execute(type, userId);
                    metrics.recordTaskOutcome(type.name(), result.outcome().name());
                    return result;
                })
                .toList();

        AgentReflection reflection = new AgentReflection(userId, plan, results, assess(plan, results), Instant.now());
        memory.remember(reflection);
        scheduler.recordRun(userId);
        metrics.recordRun();
        metrics.recordRunLatency(System.currentTimeMillis() - start);
        return reflection;
    }

    private AgentObservation observe(UUID userId) {
        AgentObservation base = observeCareerMonitor(userId);
        MissionContext missionContext = observeMission(userId);
        return missionContext == null ? base : base.withMissionContext(missionContext);
    }

    private AgentObservation observeCareerMonitor(UUID userId) {
        CareerMonitor monitor = careerMonitorProvider.getIfAvailable();
        if (monitor == null) {
            return AgentObservation.empty(userId);
        }
        try {
            CareerInsights insights = monitor.monitor(userId);
            return AgentObservation.from(userId, insights);
        } catch (Exception e) {
            log.warn("CareerMonitor observation failed for agent run, observing nothing: {}", e.toString());
            return AgentObservation.empty(userId);
        }
    }

    /**
     * Phase 7A — {@code null} unless {@code career.mission.agent.enabled} is on and {@link
     * MissionAwareDailyBriefService} (its own independent {@code career.mission.daily.enabled}
     * flag) is both available and returns a brief for this user. Never throws: a failure here
     * degrades to no mission context, same discipline as {@link #observeCareerMonitor}.
     */
    private MissionContext observeMission(UUID userId) {
        if (!missionAwareEnabled || missionBriefProvider == null) {
            return null;
        }
        MissionAwareDailyBriefService briefService = missionBriefProvider.getIfAvailable();
        if (briefService == null) {
            return null;
        }
        try {
            return briefService.buildFor(userId).map(DefaultAutonomousCareerAgent::toMissionContext).orElse(null);
        } catch (Exception e) {
            log.warn("Mission observation failed for agent run, observing no mission context: {}", e.toString());
            return null;
        }
    }

    private static MissionContext toMissionContext(MissionBrief brief) {
        return new MissionContext(brief.missionId(), brief.missionName(), brief.progressPercent(),
                brief.currentStrategyCountry(), brief.highRiskAreas(), brief.priorityWorkflows());
    }

    private String assess(AgentExecutionPlan plan, List<AgentTaskResult> results) {
        if (plan.isEmpty()) {
            return "No action needed: " + plan.reason();
        }
        long deferred = results.stream().filter(r -> r.outcome() == TaskOutcome.DEFERRED).count();
        return "Planned " + plan.tasks().size() + " task(s); " + deferred + " deferred (no live dispatch wired yet)";
    }
}
