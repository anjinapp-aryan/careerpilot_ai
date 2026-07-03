package ai.careerpilot.service.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Weekly catch-up trigger for {@link CandidateProfileBackfillService#runCapped} (Phase 1.5).
 * {@code @EnableScheduling} is already on the application class (see {@code JobDiscoveryScheduler}
 * for the established pattern this clones). Guarded by {@code candidate.profile.rebuild.enabled}
 * so it can be turned off per-environment; the cron defaults to Sunday 02:00.
 */
@Component
public class CandidateProfileRebuildScheduler {

    private static final Logger log = LoggerFactory.getLogger(CandidateProfileRebuildScheduler.class);

    private final CandidateProfileBackfillService backfillService;
    private final boolean enabled;
    private final int maxPerRun;

    public CandidateProfileRebuildScheduler(CandidateProfileBackfillService backfillService,
                                            @Value("${candidate.profile.rebuild.enabled:false}") boolean enabled,
                                            @Value("${candidate.profile.rebuild.max-per-run:200}") int maxPerRun) {
        this.backfillService = backfillService;
        this.enabled = enabled;
        this.maxPerRun = maxPerRun;
    }

    @Scheduled(cron = "${candidate.profile.rebuild.cron:0 0 2 ? * SUN}")
    public void runWeekly() {
        if (!enabled) {
            log.debug("Candidate profile scheduled rebuild disabled; skipping");
            return;
        }
        log.info("Scheduled candidate profile rebuild starting (cap={})", maxPerRun);
        backfillService.runCapped(maxPerRun);
    }
}
