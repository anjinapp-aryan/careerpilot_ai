package ai.careerpilot.api;

import ai.careerpilot.jobdiscovery.enterprise.CompanyConnector;
import ai.careerpilot.jobdiscovery.enterprise.CompanyConnectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controller Tests (Part 13) — all 8 required endpoints, plus the 404 path and the extra sync-failed endpoint. */
class EnterpriseConnectorControllerTest {

    private CompanyConnectorService service;
    private EnterpriseConnectorController controller;

    @BeforeEach
    void setUp() {
        service = mock(CompanyConnectorService.class);
        controller = new EnterpriseConnectorController(service);
    }

    @Test
    void listDelegatesWithOptionalAtsTypeFilter() {
        when(service.listConnectors("WORKDAY")).thenReturn(List.of(CompanyConnector.builder().companyName("NVIDIA").atsType("WORKDAY").build()));
        var result = controller.list("WORKDAY");
        assertEquals(1, result.size());
        verify(service).listConnectors("WORKDAY");
    }

    @Test
    void getReturns404WhenMissing() {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> controller.get(id));
    }

    @Test
    void getReturnsConnectorWhenPresent() {
        UUID id = UUID.randomUUID();
        when(service.get(id)).thenReturn(Optional.of(CompanyConnector.builder().id(id).companyName("NVIDIA").atsType("WORKDAY").build()));
        assertEquals("NVIDIA", controller.get(id).getCompanyName());
    }

    @Test
    void enableAndDisableDelegateToService() {
        UUID id = UUID.randomUUID();
        when(service.setEnabled(id, true)).thenReturn(Optional.of(CompanyConnector.builder().id(id).enabled(true).build()));
        when(service.setEnabled(id, false)).thenReturn(Optional.of(CompanyConnector.builder().id(id).enabled(false).build()));

        assertTrue(controller.enable(id).getEnabled());
        assertFalse(controller.disable(id).getEnabled());
    }

    @Test
    void enableReturns404WhenMissing() {
        UUID id = UUID.randomUUID();
        when(service.setEnabled(id, true)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> controller.enable(id));
    }

    @Test
    void syncDelegatesToService() {
        UUID id = UUID.randomUUID();
        var expected = new CompanyConnectorService.SyncResult(id, "NVIDIA", "WORKDAY", true, 5, 5, 100, null);
        when(service.syncConnector(id)).thenReturn(expected);
        assertEquals(expected, controller.sync(id));
    }

    @Test
    void syncAllAndSyncFailedDelegateToService() {
        when(service.syncAll()).thenReturn(List.of());
        when(service.syncFailedConnectors()).thenReturn(List.of());
        assertNotNull(controller.syncAll());
        assertNotNull(controller.syncFailed());
        verify(service).syncAll();
        verify(service).syncFailedConnectors();
    }

    @Test
    void statisticsAndHealthDelegateToService() {
        var stats = new CompanyConnectorService.ConnectorStatistics(3, 2, 1, 2, 1, 20, 6.67, "A", "B");
        when(service.statistics()).thenReturn(stats);

        assertEquals(stats, controller.statistics());
        var health = controller.health();
        assertEquals(3L, health.get("total"));
        assertEquals(2L, health.get("healthy"));
        assertEquals(1L, health.get("failed"));
    }
}
