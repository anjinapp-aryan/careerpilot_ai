package ai.careerpilot.execution.analytics;

import ai.careerpilot.domain.ExecutionAnalytics;
import ai.careerpilot.repo.ExecutionAnalyticsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2E.8 — writes append-only per-user metric snapshots (submitted count, success/interview/
 * offer rate, latencies) computed from execution + tracking events. Flag-gated dark by
 * {@code application.analytics.enabled}; never throws.
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final ExecutionAnalyticsRepository analytics;
    private final AnalyticsMetrics metrics;
    private final boolean enabled;

    public AnalyticsService(ExecutionAnalyticsRepository analytics, AnalyticsMetrics metrics,
                            @Value("${application.analytics.enabled:false}") boolean enabled) {
        this.analytics = analytics;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Append a metric snapshot for a user. Empty when disabled/failed. Never throws. */
    @Transactional
    public Optional<ExecutionAnalytics> record(UUID userId, String metric, BigDecimal value) {
        if (!enabled) return Optional.empty();
        metrics.recordRequest();
        try {
            ExecutionAnalytics snapshot = analytics.save(ExecutionAnalytics.builder()
                    .userId(userId).metric(metric).value(value)
                    .build());
            metrics.recordSnapshot();
            log.info("EXEC_ANALYTICS user={} metric={} value={}", userId, metric, value);
            return Optional.of(snapshot);
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("EXEC_ANALYTICS error user={} metric={}: {}", userId, metric, e.toString());
            return Optional.empty();
        }
    }

    public List<ExecutionAnalytics> forUser(UUID userId) {
        return analytics.findByUserIdOrderByComputedAtDesc(userId);
    }
}
