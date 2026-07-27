package ai.careerpilot.career.agent;

import java.time.Duration;
import java.util.UUID;

/**
 * Phase 11.6 — tracks per-user run cadence/eligibility. Deliberately NOT Spring's own {@code
 * org.springframework.scheduling.TaskScheduler} (no {@code @Scheduled} bean, no cron trigger
 * anywhere in this phase) — this is a plain eligibility check {@link AutonomousCareerAgent}
 * consults before running, matching the whole Phase 11 series' "not auto-triggered" discipline.
 * A future phase that wants a real recurring trigger would add one calling {@link
 * AutonomousCareerAgent#runOnce} on a cron, guarded by this same eligibility check.
 */
public interface TaskScheduler {

    boolean isEligibleToRun(UUID userId, Duration minInterval);

    void recordRun(UUID userId);
}
