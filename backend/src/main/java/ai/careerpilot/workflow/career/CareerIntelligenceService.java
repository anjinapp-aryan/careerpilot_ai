package ai.careerpilot.workflow.career;

import ai.careerpilot.domain.ApplicationAnalytics;
import ai.careerpilot.domain.CareerIntelligence;
import ai.careerpilot.repo.ApplicationAnalyticsRepository;
import ai.careerpilot.repo.CareerIntelligenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * Phase 3A.6 — learns per-user career probabilities from the {@link ApplicationAnalytics} snapshots
 * (deterministic; no LLM), via {@link CareerProbability}. Flag-gated dark by
 * {@code career.intelligence.enabled}; append-only; never throws.
 */
@Service
public class CareerIntelligenceService {

    private static final Logger log = LoggerFactory.getLogger(CareerIntelligenceService.class);

    private final CareerIntelligenceRepository career;
    private final ApplicationAnalyticsRepository analytics;
    private final CareerIntelligenceMetrics metrics;
    private final boolean enabled;

    public CareerIntelligenceService(CareerIntelligenceRepository career,
                                     ApplicationAnalyticsRepository analytics,
                                     CareerIntelligenceMetrics metrics,
                                     @Value("${career.intelligence.enabled:false}") boolean enabled) {
        this.career = career;
        this.analytics = analytics;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Recompute the core career probabilities for a user from their latest analytics snapshots. Returns
     * the number of dimension rows written (0 when disabled/failed). Never throws.
     */
    @Transactional
    public int recompute(UUID userId) {
        if (!enabled) return 0;
        metrics.recordRequest();
        try {
            double interviewRate = latest(userId, ApplicationAnalytics.METRIC_INTERVIEW_RATE);
            double offerRate = latest(userId, ApplicationAnalytics.METRIC_OFFER_RATE);
            int sample = (int) latest(userId, ApplicationAnalytics.METRIC_APPLICATION_COUNT);
            double careerSuccess = CareerProbability.careerSuccess(interviewRate, offerRate);

            int written = 0;
            written += write(userId, CareerIntelligence.DIM_INTERVIEW_PROBABILITY, CareerProbability.clamp(interviewRate), sample);
            written += write(userId, CareerIntelligence.DIM_OFFER_PROBABILITY, CareerProbability.clamp(offerRate), sample);
            written += write(userId, CareerIntelligence.DIM_CAREER_SUCCESS, careerSuccess, sample);
            metrics.recordRecompute();
            log.info("CAREER_INTEL recompute user={} careerSuccess={} sample={}", userId, careerSuccess, sample);
            return written;
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("CAREER_INTEL recompute error user={}: {}", userId, e.toString());
            return 0;
        }
    }

    public List<CareerIntelligence> forUser(UUID userId) {
        return career.findByUserIdOrderByComputedAtDesc(userId);
    }

    private double latest(UUID userId, String metric) {
        List<ApplicationAnalytics> rows = analytics.findByUserIdAndMetricOrderByComputedAtDesc(userId, metric);
        if (rows.isEmpty() || rows.get(0).getValue() == null) return 0.0;
        return rows.get(0).getValue().doubleValue();
    }

    private int write(UUID userId, String dimension, double probability, int sample) {
        career.save(CareerIntelligence.builder()
                .userId(userId).dimension(dimension)
                .probability(BigDecimal.valueOf(probability).setScale(4, RoundingMode.HALF_UP))
                .sampleSize(sample)
                .build());
        metrics.recordDimensionWritten();
        return 1;
    }
}
