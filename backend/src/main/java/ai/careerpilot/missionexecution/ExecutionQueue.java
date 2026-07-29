package ai.careerpilot.missionexecution;

import java.util.List;

/**
 * Pre-Phase-9 Hardening — {@link ExecutionDecision}s partitioned by immediate actionability:
 * {@code readyNow} (week 1, not blocked, not awaiting approval/retry), {@code waiting} (policy
 * {@link ExecutionPolicy#WAIT} — reserved for a future scheduler variant; the default engine
 * never produces one), {@code scheduled} (week &gt; 1).
 */
public record ExecutionQueue(List<ExecutionDecision> readyNow, List<ExecutionDecision> waiting,
                              List<ExecutionDecision> scheduled) {
}
