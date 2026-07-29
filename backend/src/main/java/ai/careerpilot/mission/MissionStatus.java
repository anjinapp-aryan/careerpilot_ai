package ai.careerpilot.mission;

/**
 * Mission Engine, Phase 1 — lifecycle status for both {@link ai.careerpilot.domain.CareerMission}
 * and {@link ai.careerpilot.domain.CareerGoal} (a goal is a milestone toward a mission; reusing
 * one status enum instead of two near-identical ones keeps this deliberately simple).
 */
public enum MissionStatus {
    ACTIVE,
    PAUSED,
    COMPLETED
}
