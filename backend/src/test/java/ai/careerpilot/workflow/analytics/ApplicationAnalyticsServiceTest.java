package ai.careerpilot.workflow.analytics;

import ai.careerpilot.domain.ApplicationAnalytics;
import ai.careerpilot.domain.ApplicationLifecycle;
import ai.careerpilot.repo.ApplicationAnalyticsRepository;
import ai.careerpilot.repo.ApplicationLifecycleRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 3A.5 — the analytics service ships DARK (disabled → no-op), recomputes outcome rates
 * deterministically from lifecycle rows, and never throws. The pure {@code rate()} helper is covered
 * across its edge cases.
 */
class ApplicationAnalyticsServiceTest {

    private final ApplicationAnalyticsRepository analytics = mock(ApplicationAnalyticsRepository.class);
    private final ApplicationLifecycleRepository lifecycles = mock(ApplicationLifecycleRepository.class);
    private final ApplicationAnalyticsMetrics metrics = new ApplicationAnalyticsMetrics();
    private final UUID userId = UUID.randomUUID();

    private ApplicationAnalyticsService svc(boolean enabled) {
        return new ApplicationAnalyticsService(analytics, lifecycles, metrics, enabled);
    }

    private ApplicationLifecycle row(String status) {
        return ApplicationLifecycle.builder().userId(userId).jobId(UUID.randomUUID()).currentStatus(status).build();
    }

    @Test
    void disabledIsNoOp() {
        assertThat(svc(false).recompute(userId)).isZero();
        assertThat(svc(false).record(userId, "X", BigDecimal.ONE, null)).isEmpty();
        verifyNoInteractions(lifecycles);
    }

    @Test
    void recomputeWritesFourMetrics() {
        when(lifecycles.findByUserId(userId)).thenReturn(List.of(
                row(ApplicationLifecycle.STATUS_SUBMITTED),
                row(ApplicationLifecycle.STATUS_TECHNICAL_INTERVIEW),
                row(ApplicationLifecycle.STATUS_OFFER_RECEIVED),
                row(ApplicationLifecycle.STATUS_REJECTED)));
        when(analytics.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(svc(true).recompute(userId)).isEqualTo(4); // count, interview_rate, offer_rate, rejection_rate
        verify(analytics, times(4)).save(any(ApplicationAnalytics.class));
    }

    @Test
    void neverThrowsOnRepoFailure() {
        when(lifecycles.findByUserId(userId)).thenThrow(new RuntimeException("db down"));
        assertThat(svc(true).recompute(userId)).isZero();
    }

    @Test
    void rateIsBoundedAndRounded() {
        assertThat(ApplicationAnalyticsService.rate(1, 3)).isEqualByComparingTo(new BigDecimal("0.3333"));
        assertThat(ApplicationAnalyticsService.rate(0, 0)).isEqualByComparingTo(new BigDecimal("0.0000"));
        assertThat(ApplicationAnalyticsService.rate(2, 2)).isEqualByComparingTo(new BigDecimal("1.0000"));
        assertThat(ApplicationAnalyticsService.rate(5, 0)).isEqualByComparingTo(BigDecimal.ZERO.setScale(4));
    }
}
