package ai.careerpilot.workflow.analytics;

import ai.careerpilot.domain.ApplicationAnalytics;
import ai.careerpilot.domain.ApplicationLifecycle;
import ai.careerpilot.repo.ApplicationAnalyticsRepository;
import ai.careerpilot.repo.ApplicationLifecycleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 3A.5 — recomputes per-user outcome analytics (application count, interview/offer/rejection
 * rates) from the lifecycle rows and writes append-only metric snapshots. Deterministic (no LLM).
 * Flag-gated dark by {@code workflow.analytics.enabled}; never throws.
 */
@Service
public class ApplicationAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationAnalyticsService.class);

    private static final Set<String> REACHED_INTERVIEW = Set.of(
            ApplicationLifecycle.STATUS_TECHNICAL_INTERVIEW, ApplicationLifecycle.STATUS_MANAGER_INTERVIEW,
            ApplicationLifecycle.STATUS_SYSTEM_DESIGN, ApplicationLifecycle.STATUS_HR_INTERVIEW,
            ApplicationLifecycle.STATUS_FINAL_ROUND, ApplicationLifecycle.STATUS_OFFER_RECEIVED,
            ApplicationLifecycle.STATUS_NEGOTIATION, ApplicationLifecycle.STATUS_ACCEPTED);
    private static final Set<String> REACHED_OFFER = Set.of(
            ApplicationLifecycle.STATUS_OFFER_RECEIVED, ApplicationLifecycle.STATUS_NEGOTIATION,
            ApplicationLifecycle.STATUS_ACCEPTED);

    private final ApplicationAnalyticsRepository analytics;
    private final ApplicationLifecycleRepository lifecycles;
    private final ApplicationAnalyticsMetrics metrics;
    private final boolean enabled;

    public ApplicationAnalyticsService(ApplicationAnalyticsRepository analytics,
                                       ApplicationLifecycleRepository lifecycles,
                                       ApplicationAnalyticsMetrics metrics,
                                       @Value("${workflow.analytics.enabled:false}") boolean enabled) {
        this.analytics = analytics;
        this.lifecycles = lifecycles;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Write a single metric snapshot. Empty when disabled/failed. Never throws. */
    @Transactional
    public Optional<ApplicationAnalytics> record(UUID userId, String metric, BigDecimal value, String dimensionKey) {
        if (!enabled) return Optional.empty();
        metrics.recordRequest();
        try {
            ApplicationAnalytics row = analytics.save(ApplicationAnalytics.builder()
                    .userId(userId).metric(metric).value(value).dimensionKey(dimensionKey)
                    .windowStart(Instant.now())
                    .build());
            metrics.recordMetricWritten();
            return Optional.of(row);
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("APP_ANALYTICS record error user={} metric={}: {}", userId, metric, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Recompute the core outcome metrics for a user from their lifecycle rows and persist snapshots.
     * Returns the number of metric rows written (0 when disabled/failed). Never throws.
     */
    @Transactional
    public int recompute(UUID userId) {
        if (!enabled) return 0;
        metrics.recordRequest();
        try {
            List<ApplicationLifecycle> rows = lifecycles.findByUserId(userId);
            int total = rows.size();
            long interviews = rows.stream().filter(r -> REACHED_INTERVIEW.contains(r.getCurrentStatus())).count();
            long offers = rows.stream().filter(r -> REACHED_OFFER.contains(r.getCurrentStatus())).count();
            long rejects = rows.stream().filter(r -> ApplicationLifecycle.STATUS_REJECTED.equals(r.getCurrentStatus())).count();

            int written = 0;
            written += write(userId, ApplicationAnalytics.METRIC_APPLICATION_COUNT, BigDecimal.valueOf(total));
            written += write(userId, ApplicationAnalytics.METRIC_INTERVIEW_RATE, rate(interviews, total));
            written += write(userId, ApplicationAnalytics.METRIC_OFFER_RATE, rate(offers, total));
            written += write(userId, ApplicationAnalytics.METRIC_REJECTION_RATE, rate(rejects, total));
            metrics.recordRecompute();
            log.info("APP_ANALYTICS recompute user={} total={} interviews={} offers={}", userId, total, interviews, offers);
            return written;
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("APP_ANALYTICS recompute error user={}: {}", userId, e.toString());
            return 0;
        }
    }

    public List<ApplicationAnalytics> forUser(UUID userId) {
        return analytics.findByUserIdOrderByComputedAtDesc(userId);
    }

    private int write(UUID userId, String metric, BigDecimal value) {
        analytics.save(ApplicationAnalytics.builder()
                .userId(userId).metric(metric).value(value).windowStart(Instant.now()).build());
        metrics.recordMetricWritten();
        return 1;
    }

    /** Pure ratio in [0,1], 4dp; 0 when denominator is 0. Package-visible for unit testing. */
    static BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }
}
