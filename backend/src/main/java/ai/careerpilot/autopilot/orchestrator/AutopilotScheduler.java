package ai.careerpilot.autopilot.orchestrator;

import ai.careerpilot.autopilot.config.AutopilotExecutorsConfig;
import ai.careerpilot.domain.User;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Phase 7.10 — daily trigger for the {@link CareerOrchestrator}. Runs after the Phase 5 discovery
 * agent (distinct cron {@code career.orchestrator.cron}, default 02:30 vs discovery's 02:00) so it
 * decides against a freshly-refreshed recommendation pool. Each candidate user's run is dispatched
 * onto the bounded {@code autopilotExecutor}; a saturated queue rejects immediately and is logged,
 * never an unbounded backlog. Gated by {@code career.orchestrator.enabled} (default off).
 */
@Component
public class AutopilotScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutopilotScheduler.class);

    private final CareerOrchestrator orchestrator;
    private final ResumeRepository resumes;
    private final UserRepository users;
    private final ThreadPoolTaskExecutor executor;
    private final boolean enabled;

    public AutopilotScheduler(CareerOrchestrator orchestrator, ResumeRepository resumes, UserRepository users,
                              @Qualifier(AutopilotExecutorsConfig.AUTOPILOT_EXECUTOR) ThreadPoolTaskExecutor executor,
                              @Value("${career.orchestrator.enabled:false}") boolean enabled) {
        this.orchestrator = orchestrator;
        this.resumes = resumes;
        this.users = users;
        this.executor = executor;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${career.orchestrator.cron:0 30 2 * * *}")
    public void runDaily() {
        if (!enabled) {
            log.debug("Career orchestrator disabled; skipping scheduled run");
            return;
        }
        runOnce();
    }

    /** Dispatch one orchestrator run per candidate user onto the bounded executor. Never throws. */
    public void runOnce() {
        List<UUID> userIds = resumes.findDistinctUserIds();
        log.info("AUTOPILOT scheduler dispatching {} user runs", userIds.size());
        for (UUID userId : userIds) {
            UUID orgId = users.findById(userId).map(User::getOrgId).orElse(null);
            try {
                executor.execute(() -> orchestrator.runForUser(userId, orgId));
            } catch (Exception e) {
                log.warn("AUTOPILOT scheduler could not dispatch user={}: {}", userId, e.toString());
            }
        }
    }
}
