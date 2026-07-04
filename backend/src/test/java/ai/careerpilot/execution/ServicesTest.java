package ai.careerpilot.execution;

import ai.careerpilot.domain.ApplicationTracking;
import ai.careerpilot.domain.ExecutionAnalytics;
import ai.careerpilot.execution.analytics.AnalyticsMetrics;
import ai.careerpilot.execution.analytics.AnalyticsService;
import ai.careerpilot.execution.tracking.ApplicationTrackingService;
import ai.careerpilot.execution.tracking.TrackingMetrics;
import ai.careerpilot.repo.ApplicationTrackingRepository;
import ai.careerpilot.repo.ExecutionAnalyticsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Phase 2E.7 / 2E.8 — the lightweight tracking + analytics services: flag-gated, append-only, never throw. */
class ServicesTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    // ── ApplicationTrackingService (2E.7) ──

    @Test
    void trackingDisabledIsANoOp() {
        ApplicationTrackingRepository repo = mock(ApplicationTrackingRepository.class);
        ApplicationTrackingService svc = new ApplicationTrackingService(repo, new TrackingMetrics(), false);
        assertThat(svc.record(UUID.randomUUID(), userId, jobId, null, ApplicationTracking.STATUS_SUBMITTED, "x")).isEmpty();
        verifyNoInteractions(repo);
    }

    @Test
    void trackingRecordsAnAppendOnlyEntryWhenEnabled() {
        ApplicationTrackingRepository repo = mock(ApplicationTrackingRepository.class);
        when(repo.save(any(ApplicationTracking.class))).thenAnswer(inv -> inv.getArgument(0));
        ApplicationTrackingService svc = new ApplicationTrackingService(repo, new TrackingMetrics(), true);
        assertThat(svc.record(UUID.randomUUID(), userId, jobId, null, ApplicationTracking.STATUS_SUBMITTED, "x")).isPresent();
        verify(repo).save(any(ApplicationTracking.class));
    }

    @Test
    void trackingNeverThrows() {
        ApplicationTrackingRepository repo = mock(ApplicationTrackingRepository.class);
        when(repo.save(any(ApplicationTracking.class))).thenThrow(new RuntimeException("db down"));
        ApplicationTrackingService svc = new ApplicationTrackingService(repo, new TrackingMetrics(), true);
        assertThat(svc.record(UUID.randomUUID(), userId, jobId, null, "SUBMITTED", "x")).isEmpty();
    }

    @Test
    void trackingTimelineDelegates() {
        ApplicationTrackingRepository repo = mock(ApplicationTrackingRepository.class);
        new ApplicationTrackingService(repo, new TrackingMetrics(), true).timeline(userId, jobId);
        verify(repo).findByUserIdAndJobIdOrderByChangedAtDesc(userId, jobId);
    }

    // ── AnalyticsService (2E.8) ──

    @Test
    void analyticsDisabledIsANoOp() {
        ExecutionAnalyticsRepository repo = mock(ExecutionAnalyticsRepository.class);
        AnalyticsService svc = new AnalyticsService(repo, new AnalyticsMetrics(), false);
        assertThat(svc.record(userId, ExecutionAnalytics.METRIC_APPLICATIONS_SUBMITTED, BigDecimal.ONE)).isEmpty();
        verifyNoInteractions(repo);
    }

    @Test
    void analyticsWritesASnapshotWhenEnabled() {
        ExecutionAnalyticsRepository repo = mock(ExecutionAnalyticsRepository.class);
        when(repo.save(any(ExecutionAnalytics.class))).thenAnswer(inv -> inv.getArgument(0));
        AnalyticsService svc = new AnalyticsService(repo, new AnalyticsMetrics(), true);
        assertThat(svc.record(userId, ExecutionAnalytics.METRIC_SUCCESS_RATE, new BigDecimal("0.5"))).isPresent();
        verify(repo).save(any(ExecutionAnalytics.class));
    }

    @Test
    void analyticsNeverThrows() {
        ExecutionAnalyticsRepository repo = mock(ExecutionAnalyticsRepository.class);
        when(repo.save(any(ExecutionAnalytics.class))).thenThrow(new RuntimeException("db down"));
        AnalyticsService svc = new AnalyticsService(repo, new AnalyticsMetrics(), true);
        assertThat(svc.record(userId, "x", BigDecimal.ZERO)).isEmpty();
    }
}
