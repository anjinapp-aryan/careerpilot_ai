package ai.careerpilot.mission;

import ai.careerpilot.domain.CareerGoal;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.StrategyPlan;
import ai.careerpilot.jobdiscovery.international.CountryMatchingCapability.CountryFitResult;
import ai.careerpilot.mission.MissionOrchestratorService.Decision;
import ai.careerpilot.mission.MissionOrchestratorService.OrchestrationResult;
import ai.careerpilot.repo.CareerGoalRepository;
import ai.careerpilot.repo.CareerMissionRepository;
import ai.careerpilot.repo.StrategyPlanRepository;
import ai.careerpilot.strategy.StrategyEvaluationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 6A — Mission-Aware Daily Coach Integration. This is a small collaborator called FROM the
 * existing {@code DailyCareerSummaryGenerator} (same composition pattern already used there for
 * {@code ExecutiveDecisionEngine} — a new class is added because the computation genuinely
 * belongs to the Mission Engine's own package, not because this is a second daily-brief pipeline).
 * {@code DailyCareerSummaryGenerator} remains the ONLY thing that writes a {@code
 * DailyCareerSummary} row; this class only supplies the extra fields.
 *
 * <p>Gated by {@code career.mission.daily.enabled} (default {@code false}) — {@link #buildFor}
 * returns {@link Optional#empty()} unconditionally when off, or when the user has no ACTIVE
 * mission, so the caller's existing behavior is byte-for-byte unchanged in both cases.
 *
 * <p>Deterministic, no LLM (reuses {@link MissionOrchestratorService}/{@link
 * StrategyEvaluationService}'s existing rule-based engines rather than inventing a new one).
 *
 * <p><b>Phase 6A.1</b> — "current strategy" / "alternative strategy" now come from {@link
 * StrategyEvaluationService#rankCountriesForMission}, not {@code CountryMatchingCapability}
 * directly. This is a dependency-inversion refinement, no behavior change: the Daily Coach should
 * never know country-specific logic exists — country ranking is a Strategy Engine concern (it
 * already owns "what should this mission's plan be"), and {@code StrategyEvaluationService}
 * itself just forwards to the same {@code CountryMatchingCapability} as before. High-risk areas
 * are derived only from real, already-computed signals (incomplete skill-building actions) —
 * never fabricated (no data source exists for something like "low recruiter activity," so it is
 * deliberately not invented here).
 *
 * <p><b>Phase 6A.1</b> — "today's mission tasks" are resolved via an optional {@link
 * WorkflowPlanner} extension point ({@code ObjectProvider<WorkflowPlanner>}), so a future
 * Workflow Planner can take over task selection without this class changing again. No planner is
 * registered today, so {@code getIfAvailable()} always returns {@code null} and {@link
 * #resolveTodaysTasks} falls back to the pre-6A.1 manual assembly — zero behavior change.
 */
@Service
public class MissionAwareDailyBriefService {

    private static final Logger log = LoggerFactory.getLogger(MissionAwareDailyBriefService.class);

    private final CareerMissionRepository missions;
    private final StrategyPlanRepository strategyPlans;
    private final CareerGoalRepository goals;
    private final MissionOrchestratorService orchestrator;
    private final StrategyEvaluationService strategyEvaluation;
    private final ObjectProvider<WorkflowPlanner> workflowPlannerProvider;
    private final boolean enabled;

    public MissionAwareDailyBriefService(CareerMissionRepository missions, StrategyPlanRepository strategyPlans,
                                          CareerGoalRepository goals, MissionOrchestratorService orchestrator,
                                          StrategyEvaluationService strategyEvaluation,
                                          ObjectProvider<WorkflowPlanner> workflowPlannerProvider,
                                          @Value("${career.mission.daily.enabled:false}") boolean enabled) {
        this.missions = missions;
        this.strategyPlans = strategyPlans;
        this.goals = goals;
        this.orchestrator = orchestrator;
        this.strategyEvaluation = strategyEvaluation;
        this.workflowPlannerProvider = workflowPlannerProvider;
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    public record MissionBrief(
            UUID missionId, String missionName, int progressPercent,
            String currentStrategyCountry, String alternativeStrategyCountry,
            List<String> todaysMissionTasks, List<String> priorityWorkflows,
            List<String> highRiskAreas, List<String> recommendedLearning,
            List<String> recommendedJobs, List<String> recommendedInterviews,
            String estimatedCompletionTimeline, String recommendation) {}

    /** Empty when disabled or the user has no ACTIVE mission — never throws. */
    public Optional<MissionBrief> buildFor(UUID userId) {
        if (!enabled) return Optional.empty();
        try {
            return missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, MissionStatus.ACTIVE)
                    .map(mission -> build(userId, mission));
        } catch (Exception e) {
            log.warn("Mission-aware daily brief failed for user={}: {}", userId, e.toString());
            return Optional.empty();
        }
    }

    private MissionBrief build(UUID userId, CareerMission mission) {
        Optional<StrategyPlan> planOpt = strategyPlans.findFirstByMissionIdOrderByGeneratedAtDesc(mission.getId());
        List<CareerGoal> actions = planOpt
                .map(p -> goals.findByStrategyPlanIdOrderByCreatedAtAsc(p.getId()))
                .orElse(List.of());

        int total = actions.size();
        long completed = actions.stream().filter(a -> a.getStatus() == MissionStatus.COMPLETED).count();
        int progressPercent = total == 0 ? 0 : (int) Math.round(completed * 100.0 / total);

        List<String> todaysTasks = resolveTodaysTasks(mission, actions);
        List<String> learning = todaysTasks.stream().filter(t -> t.startsWith("Improve ")).toList();
        List<String> risks = learning.stream()
                .map(t -> t.replaceFirst("^Improve ", "").replaceFirst(" knowledge$", "") + " gap")
                .toList();

        List<Decision> decisions = safeOrchestratorDecisions(userId, mission.getId());
        List<String> priorityWorkflows = decisions.stream().map(Decision::workflowId).toList();
        List<String> recommendedJobs = decisions.stream()
                .filter(d -> "JOB_DISCOVERY_V1".equals(d.workflowId())).map(Decision::reason).toList();
        List<String> recommendedInterviews = decisions.stream()
                .filter(d -> "INTERVIEW_PREPARATION_V1".equals(d.workflowId())).map(Decision::reason).toList();

        List<CountryFitResult> ranked = safeCountryRanking(mission);
        String current = ranked.isEmpty() ? null : ranked.get(0).displayName();
        String alternative = ranked.size() < 2 ? null : ranked.get(1).displayName();

        String timeline = estimateTimeline(planOpt.orElse(null));
        String recommendation = buildRecommendation(learning, priorityWorkflows);

        return new MissionBrief(mission.getId(), mission.getMissionStatement(), progressPercent,
                current, alternative, todaysTasks, priorityWorkflows, risks, learning,
                recommendedJobs, recommendedInterviews, timeline, recommendation);
    }

    /** The orchestrator is a real side effect (writes mission_execution) — isolated so a failure never blocks the brief. */
    private List<Decision> safeOrchestratorDecisions(UUID userId, UUID missionId) {
        try {
            OrchestrationResult result = orchestrator.run(userId, missionId);
            return result.decisions();
        } catch (Exception e) {
            log.warn("Orchestrator run failed inside daily brief for mission={}: {}", missionId, e.toString());
            return List.of();
        }
    }

    /** Phase 6A.1: routed through the Strategy Engine, which itself forwards to CountryMatchingCapability. */
    private List<CountryFitResult> safeCountryRanking(CareerMission mission) {
        try {
            return strategyEvaluation.rankCountriesForMission(mission);
        } catch (Exception e) {
            log.warn("Country ranking failed inside daily brief for mission={}: {}", mission.getId(), e.toString());
            return List.of();
        }
    }

    /**
     * Phase 6A.1 extension point: defers to a {@link WorkflowPlanner} bean if one is registered
     * (none is, today), else falls back to the original Phase 6A "all ACTIVE actions" assembly.
     */
    private List<String> resolveTodaysTasks(CareerMission mission, List<CareerGoal> actions) {
        WorkflowPlanner planner = workflowPlannerProvider.getIfAvailable();
        if (planner != null) {
            try {
                List<String> planned = planner.planTodaysTasks(mission, actions);
                if (planned != null) return planned;
            } catch (Exception e) {
                log.warn("Workflow planner failed inside daily brief for mission={}: {}", mission.getId(), e.toString());
            }
        }
        return actions.stream()
                .filter(a -> a.getStatus() == MissionStatus.ACTIVE)
                .map(CareerGoal::getTitle)
                .toList();
    }

    private static String estimateTimeline(StrategyPlan plan) {
        if (plan == null || plan.getGeneratedAt() == null || plan.getTimeframeDays() == null) return null;
        long elapsedDays = Duration.between(plan.getGeneratedAt(), Instant.now()).toDays();
        long remaining = Math.max(0, plan.getTimeframeDays() - elapsedDays);
        return "~" + remaining + " days remaining (of a " + plan.getTimeframeDays() + "-day plan)";
    }

    private static String buildRecommendation(List<String> learning, List<String> priorityWorkflows) {
        if (!learning.isEmpty() && priorityWorkflows.contains("JOB_DISCOVERY_V1")) {
            String skill = learning.get(0).replaceFirst("^Improve ", "").replaceFirst(" knowledge$", "");
            return "Delay interview applications until your " + skill + " learning milestone is complete.";
        }
        if (priorityWorkflows.contains("INTERVIEW_PREPARATION_V1")) {
            return "Applications are progressing — focus on interview preparation.";
        }
        if (!learning.isEmpty()) {
            return "Prioritize closing your open skill gaps: " + String.join(", ", learning) + ".";
        }
        return "Stay on track with today's mission tasks.";
    }
}
