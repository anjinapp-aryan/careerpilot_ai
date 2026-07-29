package ai.careerpilot.missionexecution;

import java.util.List;

/** Pre-Phase-9 Hardening — the {@link ExecutionDecision}s assigned the same week that are also safe to run in parallel (policy {@link ExecutionPolicy#PARALLEL}). */
public record ExecutionGroup(int weekNumber, List<ExecutionDecision> decisions) {
}
