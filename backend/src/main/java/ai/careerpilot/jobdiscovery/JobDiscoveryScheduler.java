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
    private final JobEmbeddingService embeddings;
    private final ai.careerpilot.jobdiscovery.enrich.JobAiEnrichmentService enrichment;
    private final ai.careerpilot.jobdiscovery.dedup.JobDuplicateDetectionService dedup;
    private final boolean enabled;

    public JobDiscoveryScheduler(JobAggregationService aggregation,
                                 JobEmbeddingService embeddings,
                                 ai.careerpilot.jobdiscovery.enrich.JobAiEnrichmentService enrichment,
                                 ai.careerpilot.jobdiscovery.dedup.JobDuplicateDetectionService dedup,
                                 @Value("${jobs.discovery.enabled:true}") boolean enabled) {
        this.aggregation = aggregation;
        this.embeddings = embeddings;
        this.enrichment = enrichment;
        this.dedup = dedup;
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
        // Embed newly-discovered jobs (capped, idempotent). No-op unless embeddings are enabled;
        // isolated so an embedding failure never affects the discovery run that just succeeded.
        try {
            embeddings.embedMissingJobs();
        } catch (Exception e) {
            log.warn("Post-discovery embedding pass failed: {}", e.toString());
        }
        // LLM-enrich newly-discovered jobs (capped, idempotent). No-op unless enrichment is enabled;
        // isolated so an enrichment failure never affects the discovery run that just succeeded.
        try {
            enrichment.enrichMissingJobs();
        } catch (Exception e) {
            log.warn("Post-discovery enrichment pass failed: {}", e.toString());
        }
        // Detect cross-source duplicates among newly-embedded jobs (capped, idempotent). No-op
        // unless dedup is enabled; isolated so a detection failure never affects the rest of the run.
        try {
            dedup.detectDuplicates();
        } catch (Exception e) {
            log.warn("Post-discovery duplicate-detection pass failed: {}", e.toString());
        }
    }
}
