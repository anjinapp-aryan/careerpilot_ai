package ai.careerpilot.api;

import ai.careerpilot.jobdiscovery.discovery.CompanyDiscoveryService;
import ai.careerpilot.jobdiscovery.enterprise.CompanyConnector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controller tests — plain Mockito mocks, no MockMvc (same pattern as EnterpriseConnectorControllerTest). */
class CompanyDiscoveryControllerTest {

    private CompanyDiscoveryService service;
    private CompanyDiscoveryController controller;

    @BeforeEach
    void setUp() {
        service = mock(CompanyDiscoveryService.class);
        controller = new CompanyDiscoveryController(service);
    }

    @Test
    void candidatesDelegatesToService() {
        when(service.pendingApproval()).thenReturn(List.of(
                CompanyConnector.builder().companyName("Acme").atsType("GREENHOUSE").discoveryStatus("PENDING_APPROVAL").build()));

        var result = controller.candidates();

        assertEquals(1, result.size());
        verify(service).pendingApproval();
    }

    @Test
    void approveDelegatesToService() {
        UUID id = UUID.randomUUID();
        var connector = CompanyConnector.builder().id(id).companyName("Acme").discoveryStatus("APPROVED").enabled(true).build();
        when(service.approve(id)).thenReturn(Optional.of(connector));

        var result = controller.approve(id);

        assertEquals("APPROVED", result.getDiscoveryStatus());
        assertTrue(result.getEnabled());
    }

    @Test
    void approveReturns404WhenMissing() {
        UUID id = UUID.randomUUID();
        when(service.approve(id)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> controller.approve(id));
    }

    @Test
    void rejectDelegatesToService() {
        UUID id = UUID.randomUUID();
        var connector = CompanyConnector.builder().id(id).companyName("Acme").discoveryStatus("REJECTED").enabled(false).build();
        when(service.reject(id)).thenReturn(Optional.of(connector));

        var result = controller.reject(id);

        assertEquals("REJECTED", result.getDiscoveryStatus());
        assertFalse(result.getEnabled());
    }

    @Test
    void rejectReturns404WhenMissing() {
        UUID id = UUID.randomUUID();
        when(service.reject(id)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> controller.reject(id));
    }

    @Test
    void runDelegatesToService() {
        var expected = new CompanyDiscoveryService.DiscoveryRunResult(3, 6, 1, 1, 0);
        when(service.discoverAll()).thenReturn(expected);

        assertEquals(expected, controller.run());
        verify(service).discoverAll();
    }
}
