package ai.careerpilot.dailydiscovery;

import ai.careerpilot.domain.DailyDiscoveryRun;
import ai.careerpilot.domain.User;
import ai.careerpilot.jobdiscovery.JobAggregationService;
import ai.careerpilot.jobdiscovery.dedup.JobDuplicateDetectionService;
import ai.careerpilot.repo.DailyDiscoveryRunRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Orchestration/isolation guards: every stage is wrapped so a failure in one never aborts the
 * run for the rest, non-ACTIVE users are skipped, and the run's terminal status reflects how much
 * of the pipeline actually succeeded (SUCCESS / PARTIAL / FAILED).
 */
class DailyJobDiscoveryCoordinatorTest {

    private JobAggregationService aggregation;
    private JobDuplicateDetectionService dedup;
    private UserRepository users;
    private DailyJobDiscoveryService discoveryService;
    private DailyCareerSummaryGenerator summaryGenerator;
    private DailyDiscoveryRunRepository runs;
    private WorkflowCorrelationService correlation;
    private WorkflowDeadLetterService deadLetters;
    private DailyJobDiscoveryMetrics metrics;
    private DailyJobDiscoveryCoordinator coordinator;

    @BeforeEach
    void setUp() {
        aggregation = mock(JobAggregationService.class);
        dedup = mock(JobDuplicateDetectionService.class);
        users = mock(UserRepository.class);
        discoveryService = mock(DailyJobDiscoveryService.class);
        summaryGenerator = mock(DailyCareerSummaryGenerator.class);
        runs = mock(DailyDiscoveryRunRepository.class);
        correlation = mock(WorkflowCorrelationService.class);
        deadLetters = mock(WorkflowDeadLetterService.class);
        metrics = mock(DailyJobDiscoveryMetrics.class);

        when(correlation.start(any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        // save() echoes back the entity, assigning an id on first insert (mimics JPA save() semantics).
        when(runs.save(any())).thenAnswer(inv -> {
            DailyDiscoveryRun r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        coordinator = new DailyJobDiscoveryCoordinator(aggregation, dedup, users, discoveryService,
                summaryGenerator, runs, correlation, deadLetters, metrics);
    }

    private User activeUser() {
        return User.builder().id(UUID.randomUUID()).status("ACTIVE").fullName("Test User").build();
    }

    @Test
    void happyPathMarksRunSuccessAndRecordsMetrics() {
        when(aggregation.discoverAll()).thenReturn(new JobAggregationService.DiscoverySummary(2, 100, 90));
        when(dedup.detectDuplicates()).thenReturn(5);
        User u = activeUser();
        when(users.findAll()).thenReturn(List.of(u));
        when(discoveryService.processUser(any(), eq(u.getId())))
                .thenReturn(new DailyJobDiscoveryService.UserDiscoverySnapshot(
                        3, 1, 1, 1, 0, 2, 1, java.math.BigDecimal.TEN,
                        List.of(), List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of()));

        UUID runId = coordinator.runOnce();

        assertNotNull(runId);
        var captor = org.mockito.ArgumentCaptor.forClass(DailyDiscoveryRun.class);
        verify(runs, atLeastOnce()).save(captor.capture());
        DailyDiscoveryRun finalRun = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(DailyDiscoveryRun.STATUS_SUCCESS, finalRun.getStatus());
        assertEquals(1, finalRun.getUsersProcessed());
        verify(summaryGenerator).generate(any(), eq(u), any());
        verify(metrics).recordRunFinished(eq("SUCCESS"), anyLong(), eq(1));
        verify(deadLetters, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void inactiveUsersAreSkipped() {
        when(aggregation.discoverAll()).thenReturn(new JobAggregationService.DiscoverySummary(1, 10, 10));
        when(dedup.detectDuplicates()).thenReturn(0);
        User suspended = User.builder().id(UUID.randomUUID()).status("SUSPENDED").build();
        when(users.findAll()).thenReturn(List.of(suspended));

        coordinator.runOnce();

        verify(discoveryService, never()).processUser(any(), any());
    }

    @Test
    void oneUserFailureIsIsolatedAndRunEndsPartial() {
        when(aggregation.discoverAll()).thenReturn(new JobAggregationService.DiscoverySummary(1, 10, 10));
        when(dedup.detectDuplicates()).thenReturn(0);
        User ok = activeUser();
        User failing = activeUser();
        when(users.findAll()).thenReturn(List.of(ok, failing));
        when(discoveryService.processUser(any(), eq(ok.getId())))
                .thenReturn(new DailyJobDiscoveryService.UserDiscoverySnapshot(
                        1, 0, 0, 0, 0, 1, 0, java.math.BigDecimal.ONE,
                        List.of(), List.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of()));
        when(discoveryService.processUser(any(), eq(failing.getId())))
                .thenThrow(new RuntimeException("boom"));

        UUID runId = coordinator.runOnce();

        assertNotNull(runId);
        verify(deadLetters).record(any(), any(), eq("PROFILE_MATCH_AND_SUMMARY"), any(), any());
        var captor = org.mockito.ArgumentCaptor.forClass(DailyDiscoveryRun.class);
        verify(runs, atLeastOnce()).save(captor.capture());
        DailyDiscoveryRun finalRun = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(DailyDiscoveryRun.STATUS_PARTIAL, finalRun.getStatus());
        assertEquals(1, finalRun.getUsersProcessed());
    }

    @Test
    void fetchFailureWithNoUsersOkMarksRunFailed() {
        when(aggregation.discoverAll()).thenThrow(new RuntimeException("provider outage"));
        when(dedup.detectDuplicates()).thenReturn(0);
        when(users.findAll()).thenReturn(List.of());

        coordinator.runOnce();

        verify(deadLetters).record(any(), any(), eq("FETCH_NORMALIZE"), any(), any());
        var captor = org.mockito.ArgumentCaptor.forClass(DailyDiscoveryRun.class);
        verify(runs, atLeastOnce()).save(captor.capture());
        DailyDiscoveryRun finalRun = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertEquals(DailyDiscoveryRun.STATUS_FAILED, finalRun.getStatus());
    }
}
