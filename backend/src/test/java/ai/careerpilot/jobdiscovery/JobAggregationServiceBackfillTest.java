package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobFetchAudit;
import ai.careerpilot.jobdiscovery.provider.JobProvider;
import ai.careerpilot.jobdiscovery.provider.RawJob;
import ai.careerpilot.kafka.WorkflowEventProducer;
import ai.careerpilot.repo.JobFetchAuditRepository;
import ai.careerpilot.repo.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Covers the two genuinely-new orchestration entry points added to {@link JobAggregationService}
 * for Phase-Providers backfill: run-one-provider ({@code discoverProvider}) and time-windowed
 * backfill ({@code discoverAll(Duration)} / the package-private {@code filterByWindow} helper).
 * Existing discoverAll() behavior is left to the pre-existing test suite; this file only exercises
 * the new surface.
 */
class JobAggregationServiceBackfillTest {

    private JobProvider configuredProvider;
    private JobProvider unconfiguredProvider;
    private JobNormalizer normalizer;
    private JobRepository jobs;
    private JobFetchAuditRepository audits;
    private WorkflowEventProducer events;
    private JobDiscoveryHealthTracker health;
    private JobAggregationService service;

    @BeforeEach
    void setUp() {
        configuredProvider = mock(JobProvider.class);
        when(configuredProvider.name()).thenReturn("ashby");
        when(configuredProvider.isConfigured()).thenReturn(true);

        unconfiguredProvider = mock(JobProvider.class);
        when(unconfiguredProvider.name()).thenReturn("smartrecruiters");
        when(unconfiguredProvider.isConfigured()).thenReturn(false);

        normalizer = mock(JobNormalizer.class);
        jobs = mock(JobRepository.class);
        audits = mock(JobFetchAuditRepository.class);
        events = mock(WorkflowEventProducer.class);
        health = mock(JobDiscoveryHealthTracker.class);

        when(audits.save(any(JobFetchAudit.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jobs.findBySourceAndExternalId(anyString(), anyString())).thenReturn(Optional.empty());
        when(normalizer.toJob(any(RawJob.class), anyString())).thenAnswer(inv -> {
            RawJob r = inv.getArgument(0);
            return Job.builder().title(r.title()).externalId(r.externalId()).build();
        });

        service = new JobAggregationService(
                List.of(configuredProvider, unconfiguredProvider), normalizer, jobs, audits, events, health);
    }

    private RawJob rawJob(String id, Instant postedDate) {
        return new RawJob(id, "Engineer", "Acme", "Remote", null, null, true,
                (BigDecimal) null, null, null, "desc", List.of(), "https://x", postedDate);
    }

    // ── discoverProvider(name) ───────────────────────────────────────────

    @Test
    void discoverProviderReturnsEmptyForUnknownName() {
        assertTrue(service.discoverProvider("nonexistent").isEmpty());
    }

    @Test
    void discoverProviderIsCaseInsensitive() {
        when(configuredProvider.fetch()).thenReturn(List.of());
        assertTrue(service.discoverProvider("ASHBY").isPresent());
    }

    @Test
    void discoverProviderReturnsZeroedSummaryWhenNotConfigured() {
        var result = service.discoverProvider("smartrecruiters");
        assertTrue(result.isPresent());
        assertEquals(0, result.get().providersRun());
        assertEquals(0, result.get().totalFetched());
        verify(unconfiguredProvider, never()).fetch();
    }

    @Test
    void discoverProviderRunsConfiguredProviderAndPersists() {
        when(configuredProvider.fetch()).thenReturn(List.of(rawJob("j1", Instant.now())));
        var result = service.discoverProvider("ashby");

        assertTrue(result.isPresent());
        assertEquals(1, result.get().providersRun());
        assertEquals(1, result.get().totalFetched());
        assertEquals(1, result.get().totalPersisted());
        verify(jobs, times(1)).save(any(Job.class));
    }

    @Test
    void discoverProviderRecordsHealthOnSuccess() {
        when(configuredProvider.fetch()).thenReturn(List.of(rawJob("j1", Instant.now())));
        service.discoverProvider("ashby");
        verify(health, times(1)).recordSuccess(eq("ashby"), anyLong(), eq(1), eq(1), eq(0));
    }

    @Test
    void discoverProviderRecordsHealthOnFailure() {
        when(configuredProvider.fetch()).thenThrow(new RuntimeException("boom"));
        service.discoverProvider("ashby");
        verify(health, times(1)).recordFailure(eq("ashby"), anyLong(), contains("boom"));
    }

    @Test
    void discoverProviderDoesNotThrowWhenProviderFetchFails() {
        when(configuredProvider.fetch()).thenThrow(new RuntimeException("boom"));
        assertDoesNotThrow(() -> service.discoverProvider("ashby"));
    }

    @Test
    void discoverProviderWithWindowAppliesFiltering() {
        Instant fresh = Instant.now();
        Instant stale = Instant.now().minus(Duration.ofDays(60));
        when(configuredProvider.fetch()).thenReturn(List.of(rawJob("fresh", fresh), rawJob("stale", stale)));

        var result = service.discoverProvider("ashby", Duration.ofHours(24));

        assertTrue(result.isPresent());
        assertEquals(2, result.get().totalFetched());
        assertEquals(1, result.get().totalPersisted());
        verify(jobs, times(1)).save(any(Job.class));
    }

    // ── discoverAll(Duration) ────────────────────────────────────────────

    @Test
    void discoverAllWithNullWindowBehavesLikeUnwindowed() {
        when(configuredProvider.fetch()).thenReturn(List.of(rawJob("j1", null)));
        var summary = service.discoverAll((Duration) null);
        assertEquals(1, summary.totalFetched());
        assertEquals(1, summary.totalPersisted());
    }

    @Test
    void discoverAllSkipsUnconfiguredProviders() {
        when(configuredProvider.fetch()).thenReturn(List.of());
        service.discoverAll();
        verify(unconfiguredProvider, never()).fetch();
    }

    @Test
    void discoverAllWithWindowExcludesOutOfWindowJobs() {
        Instant fresh = Instant.now();
        Instant stale = Instant.now().minus(Duration.ofDays(10));
        when(configuredProvider.fetch()).thenReturn(List.of(rawJob("fresh", fresh), rawJob("stale", stale)));

        var summary = service.discoverAll(Duration.ofDays(1));

        assertEquals(2, summary.totalFetched());
        assertEquals(1, summary.totalPersisted());
    }

    @Test
    void discoverAllPublishesJobEventEvenForWindowedRun() {
        when(configuredProvider.fetch()).thenReturn(List.of());
        service.discoverAll(Duration.ofHours(24));
        verify(events, times(1)).publishJobEvent(eq("job-discovery"), eq("job.discovery.completed"), any());
    }

    // ── filterByWindow (package-private helper) ──────────────────────────

    @Test
    void filterByWindowExcludesJobsWithNullPostedDate() {
        List<RawJob> raw = List.of(rawJob("no-date", null), rawJob("has-date", Instant.now()));
        var filtered = JobAggregationService.filterByWindow(raw, Duration.ofDays(1));
        assertEquals(1, filtered.size());
        assertEquals("has-date", filtered.get(0).externalId());
    }

    @Test
    void filterByWindowExcludesJobsOlderThanCutoff() {
        List<RawJob> raw = List.of(rawJob("old", Instant.now().minus(Duration.ofDays(8))));
        var filtered = JobAggregationService.filterByWindow(raw, Duration.ofDays(7));
        assertTrue(filtered.isEmpty());
    }

    @Test
    void filterByWindowIncludesJobsExactlyAtCutoffBoundary() {
        Instant now = Instant.now();
        List<RawJob> raw = List.of(rawJob("boundary", now));
        var filtered = JobAggregationService.filterByWindow(raw, Duration.ofDays(1));
        assertEquals(1, filtered.size());
    }

    @Test
    void filterByWindowOnEmptyListReturnsEmpty() {
        var filtered = JobAggregationService.filterByWindow(List.of(), Duration.ofDays(1));
        assertTrue(filtered.isEmpty());
    }

    @Test
    void filterByWindowKeepsAllWhenAllWithinWindow() {
        List<RawJob> raw = List.of(rawJob("a", Instant.now()), rawJob("b", Instant.now().minus(Duration.ofHours(1))));
        var filtered = JobAggregationService.filterByWindow(raw, Duration.ofDays(1));
        assertEquals(2, filtered.size());
    }
}
