package ai.careerpilot.jobdiscovery.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gap A — Company Discovery Agent scheduler. Deliberately a separate {@code @Component} from
 * {@link ai.careerpilot.jobdiscovery.JobDiscoveryScheduler} — company discovery is a genuinely
 * different, much lower-frequency concern (finding new companies' ATS endpoints) than job-listing
 * discovery (fetching listings from already-known sources), so it gets its own cron property and
 * its own flag rather than a second method bolted onto the existing scheduler.
 *
 * <p>{@code @EnableScheduling} is already on the application class. Gated by {@code
 * company.discovery.enabled} (default {@code false} — ships dark); cron defaults to a weekly
 * cadence since discovering new companies is inherently a low-frequency background task.
 */
@Component
public class CompanyDiscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(CompanyDiscoveryScheduler.class);

    private final CompanyDiscoveryService service;
    private final boolean enabled;

    public CompanyDiscoveryScheduler(CompanyDiscoveryService service,
                                      @Value("${company.discovery.enabled:false}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${company.discovery.cron:0 0 5 * * MON}")
    public void runWeekly() {
        if (!enabled) {
            log.debug("Company discovery disabled; skipping scheduled run");
            return;
        }
        log.info("Scheduled company discovery starting");
        service.discoverAll();
    }
}
