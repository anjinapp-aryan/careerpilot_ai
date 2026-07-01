package ai.careerpilot.jobdiscovery.lake;

import ai.careerpilot.domain.JobRaw;
import ai.careerpilot.jobdiscovery.provider.RawJob;
import ai.careerpilot.repo.JobRawRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Raw-capture idempotency (Step 4). A blank external id is skipped; an unchanged payload (same
 * checksum) re-ingested is a no-op that reuses the existing row; a first-time payload is persisted.
 */
class JobRawServiceTest {

    private JobRawRepository repo;
    private JobRawService service;

    @BeforeEach
    void setUp() {
        repo = mock(JobRawRepository.class);
        service = new JobRawService(repo);
    }

    private RawJob raw(String externalId, Map<String, Object> payload) {
        return new RawJob(externalId, "Backend Engineer", "Acme", "Berlin", "Germany", "Berlin",
                false, null, null, "EUR", "desc", null, "http://x", null, payload);
    }

    @Test
    void blankExternalIdIsSkipped() {
        assertTrue(service.capture("remoteok", raw("  ", Map.of("a", 1)), UUID.randomUUID()).isEmpty());
        assertTrue(service.capture("remoteok", raw(null, Map.of("a", 1)), UUID.randomUUID()).isEmpty());
        verify(repo, never()).save(any());
    }

    @Test
    void firstTimePayloadIsPersisted() {
        when(repo.existsByProviderAndExternalIdAndChecksum(any(), any(), any())).thenReturn(false);
        when(repo.save(any(JobRaw.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<JobRaw> out = service.capture("remoteok", raw("123", Map.of("position", "Backend")), UUID.randomUUID());

        assertTrue(out.isPresent());
        assertEquals("remoteok", out.get().getProvider());
        assertEquals("123", out.get().getExternalId());
        assertNotNull(out.get().getChecksum());
        assertNotNull(out.get().getPayloadJson());
        verify(repo).save(any(JobRaw.class));
    }

    @Test
    void unchangedPayloadReusesExistingRowWithoutSaving() {
        JobRaw existing = JobRaw.builder().provider("remoteok").externalId("123").build();
        when(repo.existsByProviderAndExternalIdAndChecksum(any(), any(), any())).thenReturn(true);
        when(repo.findFirstByProviderAndExternalIdOrderByFetchedAtDesc("remoteok", "123"))
                .thenReturn(Optional.of(existing));

        Optional<JobRaw> out = service.capture("remoteok", raw("123", Map.of("position", "Backend")), UUID.randomUUID());

        assertTrue(out.isPresent());
        assertSame(existing, out.get());
        verify(repo, never()).save(any());
    }

    @Test
    void checksumIsDeterministic() {
        String a = JobRawService.sha256("provider|123|{\"x\":1}");
        String b = JobRawService.sha256("provider|123|{\"x\":1}");
        assertEquals(a, b);
        assertEquals(64, a.length());
        assertNotEquals(a, JobRawService.sha256("provider|123|{\"x\":2}"));
    }
}
