package ai.careerpilot.dailydiscovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Phase 5A — daily 02:00 trigger for the whole discovery→analytics→summary pipeline. Distinct
 * cron property ({@code career.discovery.scheduler.cron}) and enable flag
 * ({@code career.discovery.scheduler.enabled}) from the existing 06:00
 * {@code jobs.discovery.*} scheduler ({@link ai.careerpilot.jobdiscovery.JobDiscoveryScheduler})
 * so the two never collide; that scheduler and its flags are untouched. Ships dark: default disabled.
 */
@Component
public class DailyJobDiscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyJobDiscoveryScheduler.class);

    private final DailyJobDiscoveryCoordinator coordinator;
    private final boolean enabled;

    public DailyJobDiscoveryScheduler(DailyJobDiscoveryCoordinator coordinator,
                                      @Value("${career.discovery.scheduler.enabled:false}") boolean enabled) {
        this.coordinator = coordinator;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${career.discovery.scheduler.cron:0 0 2 * * *}")
    public void runDaily() {
        if (!enabled) {
            log.debug("Daily job discovery agent disabled; skipping scheduled run");
            return;
        }
        log.info("Daily job discovery agent starting scheduled run");
        coordinator.runOnce();
    }
}
