package ai.careerpilot.api;

import ai.careerpilot.jobdiscovery.discovery.CompanyDiscoveryHealthTracker;
import ai.careerpilot.jobdiscovery.discovery.CompanyDiscoveryService;
import ai.careerpilot.jobdiscovery.discovery.CompanySource;
import ai.careerpilot.repo.CompanyConnectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Diagnostics controller tests — plain Mockito mocks, no MockMvc. */
class CompanyDiscoveryDiagnosticsControllerTest {

    private CompanyDiscoveryHealthTracker health;
    private CompanySource source;
    private CompanyConnectorRepository repo;
    private CompanyDiscoveryDiagnosticsController controller;

    @BeforeEach
    void setUp() {
        health = mock(CompanyDiscoveryHealthTracker.class);
        source = mock(CompanySource.class);
        repo = mock(CompanyConnectorRepository.class);
        controller = new CompanyDiscoveryDiagnosticsController(health, List.of(source), repo);
    }

    @Test
    void summaryAggregatesCountsAndPerSourceHealth() {
        when(source.name()).thenReturn("greenhouse");
        when(source.isConfigured()).thenReturn(true);
        when(health.snapshot("greenhouse")).thenReturn(new CompanyDiscoveryHealthTracker.Snapshot(
                "greenhouse", CompanyDiscoveryHealthTracker.CircuitState.CLOSED, 5, 2, 3, 0, 10, 8, null, null));
        when(repo.countByDiscoveryStatus(CompanyDiscoveryService.PENDING_APPROVAL)).thenReturn(2L);
        when(repo.countByDiscoveryStatus(CompanyDiscoveryService.APPROVED)).thenReturn(1L);
        when(repo.countByDiscoveryStatus(CompanyDiscoveryService.REJECTED)).thenReturn(0L);

        Map<String, Object> out = controller.summary();

        assertEquals(2L, out.get("pendingApproval"));
        assertEquals(1L, out.get("approved"));
        assertEquals(0L, out.get("rejected"));
        assertEquals(5L, out.get("candidatesProbed"));
        assertEquals(2L, out.get("hits"));
        assertTrue(((Map<?, ?>) out.get("sources")).containsKey("greenhouse"));
    }
}
