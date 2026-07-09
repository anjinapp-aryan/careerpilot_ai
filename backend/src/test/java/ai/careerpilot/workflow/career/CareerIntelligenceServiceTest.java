package ai.careerpilot.workflow.career;

import ai.careerpilot.domain.ApplicationAnalytics;
import ai.careerpilot.domain.CareerIntelligence;
import ai.careerpilot.repo.ApplicationAnalyticsRepository;
import ai.careerpilot.repo.CareerIntelligenceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 3A.6 — the career intelligence service ships DARK (disabled → no-op), derives probabilities
 * deterministically from the latest analytics snapshots, and never throws.
 */
class CareerIntelligenceServiceTest {

    private final CareerIntelligenceRepository career = mock(CareerIntelligenceRepository.class);
    private final ApplicationAnalyticsRepository analytics = mock(ApplicationAnalyticsRepository.class);
    private final CareerIntelligenceMetrics metrics = new CareerIntelligenceMetrics();
    private final UUID userId = UUID.randomUUID();

    private CareerIntelligenceService svc(boolean enabled) {
        return new CareerIntelligenceService(career, analytics, metrics, enabled);
    }

    private void stubMetric(String metric, double value) {
        when(analytics.findByUserIdAndMetricOrderByComputedAtDesc(userId, metric)).thenReturn(List.of(
                ApplicationAnalytics.builder().userId(userId).metric(metric).value(BigDecimal.valueOf(value)).build()));
    }

    @Test
    void disabledIsNoOp() {
        assertThat(svc(false).recompute(userId)).isZero();
        verifyNoInteractions(career);
    }

    @Test
    void recomputeWritesThreeDimensions() {
        stubMetric(ApplicationAnalytics.METRIC_INTERVIEW_RATE, 0.5);
        stubMetric(ApplicationAnalytics.METRIC_OFFER_RATE, 0.25);
        stubMetric(ApplicationAnalytics.METRIC_APPLICATION_COUNT, 8);
        when(career.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(svc(true).recompute(userId)).isEqualTo(3);
        ArgumentCaptor<CareerIntelligence> captor = ArgumentCaptor.forClass(CareerIntelligence.class);
        verify(career, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(CareerIntelligence::getDimension)
                .containsExactlyInAnyOrder(CareerIntelligence.DIM_INTERVIEW_PROBABILITY,
                        CareerIntelligence.DIM_OFFER_PROBABILITY, CareerIntelligence.DIM_CAREER_SUCCESS);
    }

    @Test
    void careerSuccessMatchesWeightedBlend() {
        stubMetric(ApplicationAnalytics.METRIC_INTERVIEW_RATE, 0.5);
        stubMetric(ApplicationAnalytics.METRIC_OFFER_RATE, 0.25);
        stubMetric(ApplicationAnalytics.METRIC_APPLICATION_COUNT, 4);
        when(career.save(any())).thenAnswer(inv -> inv.getArgument(0));

        svc(true).recompute(userId);
        ArgumentCaptor<CareerIntelligence> captor = ArgumentCaptor.forClass(CareerIntelligence.class);
        verify(career, times(3)).save(captor.capture());
        CareerIntelligence success = captor.getAllValues().stream()
                .filter(c -> CareerIntelligence.DIM_CAREER_SUCCESS.equals(c.getDimension())).findFirst().orElseThrow();
        // 0.4*0.5 + 0.6*0.25 = 0.35
        assertThat(success.getProbability()).isEqualByComparingTo(new BigDecimal("0.3500"));
    }

    @Test
    void missingAnalyticsYieldsZeroProbabilities() {
        when(analytics.findByUserIdAndMetricOrderByComputedAtDesc(eq(userId), any())).thenReturn(List.of());
        when(career.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(svc(true).recompute(userId)).isEqualTo(3); // still writes, all zero
    }

    @Test
    void neverThrowsOnRepoFailure() {
        when(analytics.findByUserIdAndMetricOrderByComputedAtDesc(any(), any())).thenThrow(new RuntimeException("db down"));
        assertThat(svc(true).recompute(userId)).isZero();
    }
}
