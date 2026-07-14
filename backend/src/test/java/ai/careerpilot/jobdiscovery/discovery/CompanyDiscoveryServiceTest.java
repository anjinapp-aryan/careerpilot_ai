package ai.careerpilot.jobdiscovery.discovery;

import ai.careerpilot.jobdiscovery.enterprise.CompanyConnector;
import ai.careerpilot.repo.CompanyConnectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Service tests — dedup logic, PENDING_APPROVAL insertion, isolation on source failure, approve/reject guard. */
class CompanyDiscoveryServiceTest {

    private CompanyConnectorRepository repo;
    private CompanySource hitSource;
    private CompanyDiscoveryHealthTracker health;

    private CompanyDiscoveryService service(String candidatesCsv, CompanySource... sources) {
        return new CompanyDiscoveryService(repo, List.of(sources), health, candidatesCsv);
    }

    @BeforeEach
    void setUp() {
        repo = mock(CompanyConnectorRepository.class);
        hitSource = mock(CompanySource.class);
        health = mock(CompanyDiscoveryHealthTracker.class);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void discoverAllSkipsWhenNoCandidatesConfigured() {
        when(hitSource.isConfigured()).thenReturn(true);
        var svc = service("", hitSource);

        var result = svc.discoverAll();

        assertEquals(0, result.probesRun());
        verifyNoInteractions(repo);
        verify(hitSource, never()).probe(any());
    }

    @Test
    void discoverAllSkipsWhenNoSourcesConfigured() {
        when(hitSource.isConfigured()).thenReturn(false);
        var svc = service("acme", hitSource);

        var result = svc.discoverAll();

        assertEquals(0, result.probesRun());
        verify(hitSource, never()).probe(any());
    }

    @Test
    void discoverAllInsertsNewConnectorOnHitWithPendingApprovalAndDisabled() {
        when(hitSource.isConfigured()).thenReturn(true);
        when(hitSource.name()).thenReturn("greenhouse");
        var candidate = new DiscoveredCandidate("GREENHOUSE", "Acme", "https://boards.greenhouse.io/acme", null, null, null);
        when(hitSource.probe("acme")).thenReturn(Optional.of(candidate));
        when(repo.findByAtsTypeAndCompanyName("GREENHOUSE", "Acme")).thenReturn(Optional.empty());

        var svc = service("acme", hitSource);
        var result = svc.discoverAll();

        assertEquals(1, result.probesRun());
        assertEquals(1, result.hits());
        assertEquals(1, result.inserted());
        assertEquals(0, result.duplicatesSkipped());

        var captor = org.mockito.ArgumentCaptor.forClass(CompanyConnector.class);
        verify(repo).save(captor.capture());
        CompanyConnector saved = captor.getValue();
        assertEquals("Acme", saved.getCompanyName());
        assertEquals("GREENHOUSE", saved.getAtsType());
        assertFalse(saved.getEnabled());
        assertEquals("PENDING_APPROVAL", saved.getDiscoveryStatus());
        assertEquals("greenhouse-probe", saved.getDiscoveredBy());
        assertNotNull(saved.getDiscoveredAt());
    }

    @Test
    void discoverAllSkipsDuplicateWhenConnectorAlreadyExists() {
        when(hitSource.isConfigured()).thenReturn(true);
        when(hitSource.name()).thenReturn("greenhouse");
        var candidate = new DiscoveredCandidate("GREENHOUSE", "Acme", "https://boards.greenhouse.io/acme", null, null, null);
        when(hitSource.probe("acme")).thenReturn(Optional.of(candidate));
        when(repo.findByAtsTypeAndCompanyName("GREENHOUSE", "Acme"))
                .thenReturn(Optional.of(CompanyConnector.builder().companyName("Acme").atsType("GREENHOUSE").build()));

        var svc = service("acme", hitSource);
        var result = svc.discoverAll();

        assertEquals(1, result.hits());
        assertEquals(0, result.inserted());
        assertEquals(1, result.duplicatesSkipped());
        verify(repo, never()).save(any());
    }

    @Test
    void discoverAllRecordsMissWithoutInsertingWhenProbeIsEmpty() {
        when(hitSource.isConfigured()).thenReturn(true);
        when(hitSource.name()).thenReturn("greenhouse");
        when(hitSource.probe("acme")).thenReturn(Optional.empty());

        var svc = service("acme", hitSource);
        var result = svc.discoverAll();

        assertEquals(0, result.hits());
        assertEquals(0, result.inserted());
        verify(health).recordMiss(eq("greenhouse"), anyLong());
        verify(repo, never()).save(any());
    }

    @Test
    void discoverAllIsolatesOneSourceFailureAndContinues() {
        CompanySource failing = mock(CompanySource.class);
        when(failing.isConfigured()).thenReturn(true);
        when(failing.name()).thenReturn("ashby");
        when(failing.probe("acme")).thenThrow(new RuntimeException("boom"));

        when(hitSource.isConfigured()).thenReturn(true);
        when(hitSource.name()).thenReturn("greenhouse");
        var candidate = new DiscoveredCandidate("GREENHOUSE", "Acme", "https://boards.greenhouse.io/acme", null, null, null);
        when(hitSource.probe("acme")).thenReturn(Optional.of(candidate));
        when(repo.findByAtsTypeAndCompanyName(any(), any())).thenReturn(Optional.empty());

        var svc = service("acme", failing, hitSource);
        var result = svc.discoverAll();

        assertEquals(2, result.probesRun());
        assertEquals(1, result.inserted());
        verify(health).recordFailure(eq("ashby"), anyLong(), anyString());
    }

    @Test
    void multipleCandidatesAreAllProbed() {
        when(hitSource.isConfigured()).thenReturn(true);
        when(hitSource.name()).thenReturn("greenhouse");
        when(hitSource.probe(anyString())).thenReturn(Optional.empty());

        var svc = service("acme, globex , initech", hitSource);
        var result = svc.discoverAll();

        assertEquals(3, result.probesRun());
        verify(hitSource).probe("acme");
        verify(hitSource).probe("globex");
        verify(hitSource).probe("initech");
    }

    // ── approve / reject ────────────────────────────────────────────────────

    @Test
    void approveFlipsStatusAndEnablesConnector() {
        UUID id = UUID.randomUUID();
        CompanyConnector c = CompanyConnector.builder().id(id).companyName("Acme").atsType("GREENHOUSE")
                .enabled(false).discoveryStatus("PENDING_APPROVAL").build();
        when(repo.findById(id)).thenReturn(Optional.of(c));

        var result = service("", hitSource).approve(id);

        assertTrue(result.isPresent());
        assertEquals("APPROVED", result.get().getDiscoveryStatus());
        assertTrue(result.get().getEnabled());
    }

    @Test
    void rejectFlipsStatusAndKeepsDisabled() {
        UUID id = UUID.randomUUID();
        CompanyConnector c = CompanyConnector.builder().id(id).companyName("Acme").atsType("GREENHOUSE")
                .enabled(false).discoveryStatus("PENDING_APPROVAL").build();
        when(repo.findById(id)).thenReturn(Optional.of(c));

        var result = service("", hitSource).reject(id);

        assertTrue(result.isPresent());
        assertEquals("REJECTED", result.get().getDiscoveryStatus());
        assertFalse(result.get().getEnabled());
    }

    @Test
    void approveIsNoOpOnAlreadyRejectedConnector() {
        UUID id = UUID.randomUUID();
        CompanyConnector c = CompanyConnector.builder().id(id).companyName("Acme").atsType("GREENHOUSE")
                .enabled(false).discoveryStatus("REJECTED").build();
        when(repo.findById(id)).thenReturn(Optional.of(c));

        var result = service("", hitSource).approve(id);

        assertTrue(result.isPresent());
        assertEquals("REJECTED", result.get().getDiscoveryStatus());
        assertFalse(result.get().getEnabled());
        verify(repo, never()).save(any());
    }

    @Test
    void rejectIsNoOpOnAlreadyApprovedConnector() {
        UUID id = UUID.randomUUID();
        CompanyConnector c = CompanyConnector.builder().id(id).companyName("Acme").atsType("GREENHOUSE")
                .enabled(true).discoveryStatus("APPROVED").build();
        when(repo.findById(id)).thenReturn(Optional.of(c));

        var result = service("", hitSource).reject(id);

        assertTrue(result.isPresent());
        assertEquals("APPROVED", result.get().getDiscoveryStatus());
        assertTrue(result.get().getEnabled());
        verify(repo, never()).save(any());
    }

    @Test
    void approveReturnsEmptyWhenConnectorNotFound() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());
        assertTrue(service("", hitSource).approve(id).isEmpty());
    }

    @Test
    void pendingApprovalDelegatesToRepository() {
        when(repo.findByDiscoveryStatus("PENDING_APPROVAL")).thenReturn(List.of(
                CompanyConnector.builder().companyName("Acme").atsType("GREENHOUSE").discoveryStatus("PENDING_APPROVAL").build()));

        var result = service("", hitSource).pendingApproval();

        assertEquals(1, result.size());
        verify(repo).findByDiscoveryStatus("PENDING_APPROVAL");
    }
}
