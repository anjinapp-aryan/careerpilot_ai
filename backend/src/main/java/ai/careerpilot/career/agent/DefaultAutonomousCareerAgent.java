package ai.careerpilot.career.agent;

import ai.careerpilot.career.monitor.CareerInsights;
import ai.careerpilot.career.monitor.CareerMonitor;
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

    public DefaultAutonomousCareerAgent(ObjectProvider<CareerMonitor> careerMonitorProvider, AgentPlanner planner,
                                         AgentTaskExecutor executor, TaskScheduler scheduler, AgentMemory memory,
                                         AgentMetrics metrics, Duration minRunInterval) {
        this.careerMonitorProvider = careerMonitorProvider;
        this.planner = planner;
        this.executor = executor;
        this.scheduler = scheduler;
        this.memory = memory;
        this.metrics = metrics;
        this.minRunInterval = minRunInterval;
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

    private String assess(AgentExecutionPlan plan, List<AgentTaskResult> results) {
        if (plan.isEmpty()) {
            return "No action needed: " + plan.reason();
        }
        long deferred = results.stream().filter(r -> r.outcome() == TaskOutcome.DEFERRED).count();
        return "Planned " + plan.tasks().size() + " task(s); " + deferred + " deferred (no live dispatch wired yet)";
    }
}
