package ai.careerpilot.jobdiscovery.enterprise;

import ai.careerpilot.jobdiscovery.JobAggregationService;
import ai.careerpilot.jobdiscovery.provider.RawJob;
import ai.careerpilot.jobdiscovery.provider.SuccessFactorsProvider;
import ai.careerpilot.jobdiscovery.provider.TaleoProvider;
import ai.careerpilot.jobdiscovery.provider.WorkdayCompanyConnector;
import ai.careerpilot.jobdiscovery.provider.WorkdayProvider;
import ai.careerpilot.repo.CompanyConnectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Service Tests (Part 13) — bootstrap-from-CSV, enable/disable, sync (success/failure), statistics. */
class CompanyConnectorServiceTest {

    private CompanyConnectorRepository repo;
    private WorkdayProvider workday;
    private TaleoProvider taleo;
    private SuccessFactorsProvider successFactors;
    private JobAggregationService aggregation;
    private CompanyConnectorService service;

    @BeforeEach
    void setUp() {
        repo = mock(CompanyConnectorRepository.class);
        workday = mock(WorkdayProvider.class);
        taleo = mock(TaleoProvider.class);
        successFactors = mock(SuccessFactorsProvider.class);
        aggregation = mock(JobAggregationService.class);
        when(taleo.companies()).thenReturn(List.of());
        when(successFactors.companies()).thenReturn(List.of());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new CompanyConnectorService(repo, workday, taleo, successFactors, aggregation);
    }

    @Test
    void bootstrapSeedsFromCsvConfiguredCompaniesWhenTableIsEmpty() {
        when(repo.count()).thenReturn(0L);
        when(workday.companies()).thenReturn(List.of(
                new WorkdayCompanyConnector("nvidia", "wd5", "NVIDIAExternalCareerSite", "NVIDIA", "US", "Technology", 1)));

        service.bootstrapIfEmpty();

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        List<CompanyConnector> saved = captor.getValue();
        assertEquals(1, saved.size());
        assertEquals("NVIDIA", saved.get(0).getCompanyName());
        assertEquals("WORKDAY", saved.get(0).getAtsType());
        assertEquals("nvidia", saved.get(0).getTenant());
    }

    @Test
    void bootstrapDoesNothingWhenTableAlreadyHasRows() {
        when(repo.count()).thenReturn(5L);
        service.bootstrapIfEmpty();
        verify(repo, never()).saveAll(any());
    }

    @Test
    void syncConnectorSuccessUpdatesHealthAndImportCount() {
        UUID id = UUID.randomUUID();
        CompanyConnector c = CompanyConnector.builder()
                .id(id).companyName("NVIDIA").atsType("WORKDAY")
                .tenant("nvidia").cluster("wd5").site("NVIDIAExternalCareerSite")
                .enabled(true).failureCount(0).averageLatencyMs(0L).jobsImported(0)
                .build();
        when(repo.findById(id)).thenReturn(Optional.of(c));
        when(workday.fetchOne(any())).thenReturn(List.of(
                new RawJob("job1", "Title", "NVIDIA", "Santa Clara", "US", null, false, null, null, null, null, List.of(), "https://x", null)));
        when(aggregation.persistRawJobs(eq("workday"), anyList())).thenReturn(1);

        var result = service.syncConnector(id);

        assertTrue(result.success());
        assertEquals(1, result.jobsFetched());
        assertEquals(1, result.jobsImported());
        assertEquals("UP", c.getHealthStatus());
        assertEquals(0, c.getFailureCount());
        assertEquals(1, c.getJobsImported());
        assertNotNull(c.getLastSuccessfulSync());
    }

    @Test
    void syncConnectorFailureRecordsFailureAndDegradesHealth() {
        UUID id = UUID.randomUUID();
        CompanyConnector c = CompanyConnector.builder()
                .id(id).companyName("NVIDIA").atsType("WORKDAY")
                .tenant("nvidia").cluster("wd5").site("NVIDIAExternalCareerSite")
                .enabled(true).failureCount(0)
                .build();
        when(repo.findById(id)).thenReturn(Optional.of(c));
        when(workday.fetchOne(any())).thenThrow(new RuntimeException("boom"));

        var result = service.syncConnector(id);

        assertFalse(result.success());
        assertTrue(result.error().contains("boom"));
        assertEquals(1, c.getFailureCount());
        assertEquals("DEGRADED", c.getHealthStatus());
        assertNotNull(c.getLastFailure());
    }

    @Test
    void syncConnectorSkipsDisabledConnector() {
        UUID id = UUID.randomUUID();
        CompanyConnector c = CompanyConnector.builder().id(id).companyName("X").atsType("WORKDAY").enabled(false).build();
        when(repo.findById(id)).thenReturn(Optional.of(c));

        var result = service.syncConnector(id);

        assertFalse(result.success());
        assertEquals("connector is disabled", result.error());
        verifyNoInteractions(aggregation);
    }

    @Test
    void taleoAndSuccessFactorsConnectorsAlwaysNoOpOnSync() {
        UUID id = UUID.randomUUID();
        CompanyConnector c = CompanyConnector.builder().id(id).companyName("Globex").atsType("TALEO").enabled(true).build();
        when(repo.findById(id)).thenReturn(Optional.of(c));

        var result = service.syncConnector(id);

        assertTrue(result.success());
        assertEquals(0, result.jobsFetched());
        verifyNoInteractions(aggregation);
    }

    @Test
    void enableAndDisableToggleFlag() {
        UUID id = UUID.randomUUID();
        CompanyConnector c = CompanyConnector.builder().id(id).companyName("X").atsType("WORKDAY").enabled(true).build();
        when(repo.findById(id)).thenReturn(Optional.of(c));

        service.setEnabled(id, false);
        assertFalse(c.getEnabled());

        service.setEnabled(id, true);
        assertTrue(c.getEnabled());
    }

    @Test
    void statisticsAggregatesAcrossConnectors() {
        when(repo.count()).thenReturn(2L);
        List<CompanyConnector> all = new ArrayList<>(List.of(
                CompanyConnector.builder().companyName("A").atsType("WORKDAY").enabled(true).healthStatus("UP").jobsImported(10).failureCount(0).build(),
                CompanyConnector.builder().companyName("B").atsType("WORKDAY").enabled(false).healthStatus("DOWN").jobsImported(2).failureCount(5).build()));
        when(repo.findAll()).thenReturn(all);

        var stats = service.statistics();

        assertEquals(2, stats.total());
        assertEquals(1, stats.enabled());
        assertEquals(1, stats.disabled());
        assertEquals(1, stats.healthy());
        assertEquals(1, stats.failed());
        assertEquals(12, stats.jobsImportedTotal());
        assertEquals(6.0, stats.averageJobsPerConnector());
        assertEquals("A", stats.topPerformingConnector());
        assertEquals("B", stats.worstConnector());
    }

    @Test
    void syncFailedConnectorsOnlyTargetsUnhealthyOnes() {
        UUID healthyId = UUID.randomUUID();
        UUID downId = UUID.randomUUID();
        CompanyConnector healthy = CompanyConnector.builder().id(healthyId).companyName("Healthy").atsType("WORKDAY").enabled(true).healthStatus("UP").failureCount(0).build();
        CompanyConnector down = CompanyConnector.builder().id(downId).companyName("Down").atsType("WORKDAY").enabled(true).healthStatus("DOWN").failureCount(3)
                .tenant("acme").cluster("wd1").site("External").build();
        when(repo.findByEnabledTrue()).thenReturn(List.of(healthy, down));
        when(repo.findById(downId)).thenReturn(Optional.of(down));
        when(workday.fetchOne(any())).thenReturn(List.of());

        var results = service.syncFailedConnectors();

        assertEquals(1, results.size());
        assertEquals("Down", results.get(0).company());
    }
}
