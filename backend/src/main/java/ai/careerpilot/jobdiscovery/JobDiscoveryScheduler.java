package ai.careerpilot.jobdiscovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily discovery trigger. {@code @EnableScheduling} is already on the application class.
 * Guarded by {@code jobs.discovery.enabled} so it can be turned off per-environment; the
 * cron defaults to 06:00 daily and can be tightened to hourly later with no code change.
 */
@Component
public class JobDiscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobDiscoveryScheduler.class);

    private final JobAggregationService aggregation;
    private final boolean enabled;

    public JobDiscoveryScheduler(JobAggregationService aggregation,
                                 @Value("${jobs.discovery.enabled:true}") boolean enabled) {
        this.aggregation = aggregation;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${jobs.discovery.cron:0 0 6 * * *}")
    public void runDaily() {
        if (!enabled) {
            log.debug("Job discovery disabled; skipping scheduled run");
            return;
        }
        log.info("Scheduled job discovery starting");
        aggregation.discoverAll();
    }
}
