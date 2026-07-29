package ai.careerpilot.strategy;

import ai.careerpilot.domain.CareerGoal;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.StrategyPlan;
import ai.careerpilot.jobdiscovery.international.CountryMatchingCapability;
import ai.careerpilot.jobdiscovery.international.CountryMatchingCapability.CountryFitResult;
import ai.careerpilot.mission.MissionNotFoundException;
import ai.careerpilot.mission.MissionStatus;
import ai.careerpilot.repo.CareerGoalRepository;
import ai.careerpilot.repo.CareerMissionRepository;
import ai.careerpilot.repo.StrategyPlanRepository;
import ai.careerpilot.service.profile.JsonLists;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Strategy Engine, Phase 3 — converts a {@link CareerMission} into an actionable {@link
 * StrategyPlan} + its {@link CareerGoal} actions. <b>Rule-based today, deterministic, no LLM</b>
 * — the phase spec explicitly calls this out as the Phase 1 approach, with an LLM-generated
 * (Spring AI) version as a stated future extension point, same "rule-based now, AI later"
 * discipline as {@link ai.careerpilot.jobdiscovery.international.CountryMatchingCapability}.
 * Never touches {@code career_strategy} (Phase 6.6's single-row probability/roadmap snapshot) —
 * a genuinely different concern (computed analysis vs. an actionable, completable task list).
 *
 * <p><b>Phase 6A.1</b> — also exposes {@link #rankCountriesForMission}, a thin delegate to {@link
 * CountryMatchingCapability}. This is a dependency-inversion move, not new logic: country ranking
 * is conceptually part of "strategy" (current-country vs. alternative-country IS a strategy
 * choice), so callers outside this package (e.g. the Daily Coach) should depend on the Strategy
 * Engine as their one strategy abstraction, never reach into {@code jobdiscovery.international}
 * directly. {@link CountryMatchingCapability} itself is unchanged — it still owns the actual
 * scoring/explanation logic; this class only forwards to it.
 */
@Service
public class StrategyEvaluationService {

    private static final int DEFAULT_TIMEFRAME_DAYS = 90;
    private static final int MAX_TIMEFRAME_DAYS = 730; // 24 months, matching the phase's own worked example

    private final CareerMissionRepository missions;
    private final StrategyPlanRepository plans;
    private final CareerGoalRepository goals;
    private final CountryMatchingCapability countryMatching;

    public StrategyEvaluationService(CareerMissionRepository missions, StrategyPlanRepository plans,
                                      CareerGoalRepository goals, CountryMatchingCapability countryMatching) {
        this.missions = missions;
        this.plans = plans;
        this.goals = goals;
        this.countryMatching = countryMatching;
    }

    /**
     * Ranked country fit for this mission — current strategy (top result) vs. alternative
     * strategy (runner-up), the same shape {@link CountryMatchingCapability#rankForMission}
     * already produces. Delegation only; no new ranking logic here.
     */
    public List<CountryFitResult> rankCountriesForMission(CareerMission mission) {
        return countryMatching.rankForMission(mission);
    }

    public record GeneratedStrategy(StrategyPlan plan, List<CareerGoal> actions) {}

    /**
     * Generates a new plan + its actions for the given mission and persists both. Always inserts
     * a new {@link StrategyPlan} row (never updates a prior one) — the plan table is itself the
     * history.
     */
    @Transactional
    public GeneratedStrategy generate(UUID userId, UUID missionId) {
        CareerMission mission = missions.findByIdAndUserId(missionId, userId)
                .orElseThrow(() -> new MissionNotFoundException(missionId));

        StrategyPlan plan = plans.save(StrategyPlan.builder()
                .missionId(mission.getId())
                .timeframeDays(timeframeFor(mission))
                .status(MissionStatus.ACTIVE)
                .generatedAt(Instant.now())
                .build());

        List<CareerGoal> actions = buildActions(mission, plan.getId());
        List<CareerGoal> saved = new ArrayList<>();
        for (CareerGoal action : actions) {
            saved.add(goals.save(action));
        }
        return new GeneratedStrategy(plan, saved);
    }

    /** The latest generated plan + its actions for a mission, or empty if none has been generated yet. */
    public GeneratedStrategy latest(UUID userId, UUID missionId) {
        CareerMission mission = missions.findByIdAndUserId(missionId, userId)
                .orElseThrow(() -> new MissionNotFoundException(missionId));
        StrategyPlan plan = plans.findFirstByMissionIdOrderByGeneratedAtDesc(mission.getId())
                .orElseThrow(() -> new NoSuchElementException("No strategy plan generated yet for mission: " + missionId));
        return new GeneratedStrategy(plan, goals.findByStrategyPlanIdOrderByCreatedAtAsc(plan.getId()));
    }

    /** Marks one action complete. Ownership is enforced via the parent mission's userId. */
    @Transactional
    public CareerGoal completeAction(UUID userId, UUID actionId) {
        CareerGoal action = goals.findById(actionId)
                .orElseThrow(() -> new NoSuchElementException("Strategy action not found: " + actionId));
        // Ownership check: the action's mission must belong to this user (same 404-not-403
        // convention as MissionNotFoundException — see its javadoc for the rationale).
        missions.findByIdAndUserId(action.getMissionId(), userId)
                .orElseThrow(() -> new NoSuchElementException("Strategy action not found: " + actionId));
        action.setStatus(MissionStatus.COMPLETED);
        return goals.save(action);
    }

    private int timeframeFor(CareerMission mission) {
        Integer months = mission.getTimelineMonths();
        if (months == null || months <= 0) return DEFAULT_TIMEFRAME_DAYS;
        return Math.min(MAX_TIMEFRAME_DAYS, months * 30);
    }

    /**
     * Deterministic action generation: one "improve X" action per skill-to-acquire, one "apply
     * for the target role" action, plus "practice system design" for a senior-track mission —
     * exactly the phase spec's own worked example (Kubernetes, Spring AI MCP project, apply
     * architect positions, practice system design).
     */
    private List<CareerGoal> buildActions(CareerMission mission, UUID planId) {
        List<CareerGoal> actions = new ArrayList<>();
        for (String skill : JsonLists.toList(mission.getSkillsToAcquireJson())) {
            actions.add(action(mission, planId, "Improve " + skill + " knowledge",
                    "Build hands-on experience with " + skill + " toward your mission's target role."));
        }
        if (mission.getTargetRole() != null && !mission.getTargetRole().isBlank()) {
            actions.add(action(mission, planId, "Apply for " + mission.getTargetRole() + " positions",
                    "Start applying once your skill gaps are closing and your resume reflects them."));
        }
        if (isSeniorTarget(mission.getTargetLevel())) {
            actions.add(action(mission, planId, "Practice system design",
                    "Senior/staff/principal interviews weight system design heavily — schedule regular practice."));
        }
        return actions;
    }

    private static CareerGoal action(CareerMission mission, UUID planId, String title, String description) {
        return CareerGoal.builder()
                .missionId(mission.getId())
                .strategyPlanId(planId)
                .title(title)
                .description(description)
                .status(MissionStatus.ACTIVE)
                .build();
    }

    private static boolean isSeniorTarget(String targetLevel) {
        if (targetLevel == null) return false;
        String l = targetLevel.toLowerCase();
        return l.contains("principal") || l.contains("staff") || l.contains("director")
                || l.contains("architect") || l.contains("lead") || l.contains("head");
    }
}
