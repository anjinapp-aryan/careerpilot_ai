package ai.careerpilot.workflow.timeline;

import ai.careerpilot.domain.ApplicationTimeline;
import ai.careerpilot.repo.ApplicationTimelineRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3A.2 — appends human-readable timeline entries for an application. Flag-gated dark by
 * {@code workflow.timeline.enabled}; append-only; never throws.
 */
@Service
public class TimelineService {

    private static final Logger log = LoggerFactory.getLogger(TimelineService.class);

    private final ApplicationTimelineRepository timeline;
    private final TimelineMetrics metrics;
    private final boolean enabled;

    public TimelineService(ApplicationTimelineRepository timeline, TimelineMetrics metrics,
                           @Value("${workflow.timeline.enabled:false}") boolean enabled) {
        this.timeline = timeline;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Transactional
    public Optional<ApplicationTimeline> append(UUID userId, UUID jobId, String eventType,
                                                String eventSource, Double confidence, String details) {
        if (!enabled) return Optional.empty();
        metrics.recordRequest();
        try {
            ApplicationTimeline entry = timeline.save(ApplicationTimeline.builder()
                    .userId(userId).jobId(jobId)
                    .eventType(eventType).eventSource(eventSource)
                    .confidence(confidence).details(details)
                    .build());
            metrics.recordAppend();
            log.info("APP_TIMELINE user={} job={} event={}", userId, jobId, eventType);
            return Optional.of(entry);
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("APP_TIMELINE error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        }
    }

    public List<ApplicationTimeline> forJob(UUID userId, UUID jobId) {
        return timeline.findByUserIdAndJobIdOrderByOccurredAtDesc(userId, jobId);
    }
}
