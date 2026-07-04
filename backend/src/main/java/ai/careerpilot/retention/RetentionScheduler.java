package ai.careerpilot.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily trigger for {@link RetentionService}. {@code @EnableScheduling} is already on the application
 * class. Disabled by default ({@code retention.enabled=false}) and the service itself re-checks the flag,
 * so with stock config this fires but purges nothing. Cron defaults to 03:30 daily (off-peak) and is
 * overridable via {@code retention.cron} with no code change.
 */
@Component
public class RetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);

    private final RetentionService retention;

    @Value("${retention.enabled:false}") private boolean enabled;

    public RetentionScheduler(RetentionService retention) {
        this.retention = retention;
    }

    @Scheduled(cron = "${retention.cron:0 30 3 * * *}")
    public void runDaily() {
        if (!enabled) {
            log.debug("Retention disabled; skipping scheduled purge");
            return;
        }
        log.info("Scheduled retention purge starting");
        try {
            retention.purgeAll();
        } catch (Exception e) {
            log.warn("Scheduled retention purge failed: {}", e.toString());
        }
    }
}
