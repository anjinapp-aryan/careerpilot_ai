package ai.careerpilot.jobdiscovery.lake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily Job Lake discovery trigger (Phase 2A Step 3) at <b>02:00 UTC</b>. Intentionally separate
 * from the legacy {@link ai.careerpilot.jobdiscovery.JobDiscoveryScheduler} (06:00, writes into
 * {@code jobs}) — this drives only the SHADOW lake pipeline and shares nothing with it.
 *
 * <p>The orchestrator already enforces the {@code JOB_DISCOVERY_ENABLED} flag and the advisory lock,
 * so this class stays a thin cron wrapper. {@code @EnableScheduling} is already on the application
 * class. The 02:00 UTC slot is fixed via the {@code zone} attribute regardless of server timezone.
 */
@Component
public class JobLakeDiscoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobLakeDiscoveryScheduler.class);

    private final JobLakeDiscoveryOrchestrator orchestrator;

    public JobLakeDiscoveryScheduler(JobLakeDiscoveryOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Scheduled(cron = "${jobs.discovery.lake.cron:0 0 2 * * *}", zone = "UTC")
    public void runDaily() {
        if (!orchestrator.isEnabled()) {
            log.debug("Lake discovery disabled; skipping scheduled 02:00 UTC run");
            return;
        }
        try {
            orchestrator.run();
        } catch (Exception e) {
            // The scheduler must never propagate — a failed run is logged and retried next day.
            log.warn("Scheduled lake discovery run failed: {}", e.toString());
        }
    }
}
