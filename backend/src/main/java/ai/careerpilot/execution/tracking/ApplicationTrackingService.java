package ai.careerpilot.execution.tracking;

import ai.careerpilot.domain.ApplicationTracking;
import ai.careerpilot.repo.ApplicationTrackingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2E.7 — records the post-submission status timeline of an application as append-only rows.
 * Never mutates the org-scoped {@code applications} kanban row (links to it via {@code applicationId}
 * only). Flag-gated dark by {@code application.tracking.enabled}; never throws.
 */
@Service
public class ApplicationTrackingService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationTrackingService.class);

    private final ApplicationTrackingRepository tracking;
    private final TrackingMetrics metrics;
    private final boolean enabled;

    public ApplicationTrackingService(ApplicationTrackingRepository tracking, TrackingMetrics metrics,
                                      @Value("${application.tracking.enabled:false}") boolean enabled) {
        this.tracking = tracking;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Append a status entry to an application's timeline. Empty when disabled/failed. Never throws. */
    @Transactional
    public Optional<ApplicationTracking> record(UUID executionId, UUID userId, UUID jobId,
                                                UUID applicationId, String status, String note) {
        if (!enabled) return Optional.empty();
        metrics.recordRequest();
        try {
            ApplicationTracking entry = tracking.save(ApplicationTracking.builder()
                    .applicationExecutionId(executionId)
                    .userId(userId).jobId(jobId).applicationId(applicationId)
                    .status(status).note(note)
                    .build());
            metrics.recordTransition();
            log.info("APP_TRACKING user={} job={} status={}", userId, jobId, status);
            return Optional.of(entry);
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("APP_TRACKING error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        }
    }

    public List<ApplicationTracking> timeline(UUID userId, UUID jobId) {
        return tracking.findByUserIdAndJobIdOrderByChangedAtDesc(userId, jobId);
    }
}
