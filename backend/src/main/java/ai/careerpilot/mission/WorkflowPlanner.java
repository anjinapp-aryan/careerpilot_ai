package ai.careerpilot.mission;

import ai.careerpilot.domain.CareerGoal;
import ai.careerpilot.domain.CareerMission;

import java.util.List;

/**
 * Phase 6A.1 — extension point only, no implementation shipped in this phase (per its own
 * explicit scope: introduce the seam, do not build the planner). Future phases can register a
 * {@code @Component} implementing this interface to decide "today's mission tasks" with real
 * planning logic (dependency-aware sequencing, effort estimation, etc.) instead of
 * {@link MissionAwareDailyBriefService}'s current plain "all ACTIVE actions" assembly.
 *
 * <p>{@link MissionAwareDailyBriefService} resolves this via {@code ObjectProvider<WorkflowPlanner>}
 * — when no bean exists (true today), {@code getIfAvailable()} returns {@code null} and the
 * service falls back to its existing manual assembly unchanged. Adding a real implementation
 * later requires zero changes to {@link MissionAwareDailyBriefService} itself.
 */
public interface WorkflowPlanner {

    /** Returns the ordered list of task titles for "today" given a mission and its open/closed actions. */
    List<String> planTodaysTasks(CareerMission mission, List<CareerGoal> actions);
}
