package ai.careerpilot.jobdiscovery.lake;

import ai.careerpilot.domain.JobDuplicateMapping;
import ai.careerpilot.domain.JobNormalized;
import ai.careerpilot.jobdiscovery.dedup.DuplicateScoring;
import ai.careerpilot.repo.JobDuplicateMappingRepository;
import ai.careerpilot.repo.JobNormalizedRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Deterministic lake dedup (Step 6). Flag off → every job is its own canonical (no-op). Flag on →
 * a same-company, same-(country,city), strong-title-overlap job maps onto the existing canonical,
 * and an identical checksum short-circuits to a duplicate. Pure Mockito over a real
 * {@link DuplicateScoring}.
 */
class JobLakeDeduplicationServiceTest {

    private JobNormalizedRepository normalized;
    private JobDuplicateMappingRepository mappings;
    private final Map<UUID, JobDuplicateMapping> table = new HashMap<>();

    @BeforeEach
    void setUp() {
        normalized = mock(JobNormalizedRepository.class);
        mappings = mock(JobDuplicateMappingRepository.class);
        table.clear();
        when(mappings.existsBySourceJob(any())).thenAnswer(inv -> table.containsKey(inv.getArgument(0)));
        when(mappings.findBySourceJob(any())).thenAnswer(inv -> Optional.ofNullable(table.get(inv.getArgument(0))));
        when(mappings.save(any(JobDuplicateMapping.class))).thenAnswer(inv -> {
            JobDuplicateMapping m = inv.getArgument(0);
            table.put(m.getSourceJob(), m);
            return m;
        });
    }

    private JobLakeDeduplicationService service(boolean enabled) {
        return new JobLakeDeduplicationService(normalized, mappings, new DuplicateScoring(), enabled, 0.6);
    }

    private JobNormalized job(String company, String title, String city, String checksum) {
        JobNormalized j = new JobNormalized();
        j.setId(UUID.randomUUID());
        j.setCompany(company);
        j.setTitle(title);
        j.setCountry("Germany");
        j.setCity(city);
        j.setChecksum(checksum);
        return j;
    }

    @Test
    void disabledReturnsOwnIdAndWritesNothing() {
        JobNormalized j = job("Acme", "Backend Engineer", "Berlin", "c1");
        assertEquals(j.getId(), service(false).deduplicate(j));
        verify(mappings, never()).save(any());
    }

    @Test
    void firstMemberBecomesItsOwnCanonical() {
        JobNormalized j = job("Acme", "Backend Engineer", "Berlin", "c1");
        when(normalized.findDedupCandidates("Germany", "Berlin")).thenReturn(List.of(j));
        assertEquals(j.getId(), service(true).deduplicate(j));
        assertTrue(table.containsKey(j.getId()));
        assertEquals(j.getId(), table.get(j.getId()).getCanonicalJob());
    }

    @Test
    void matchingJobMapsOntoExistingCanonical() {
        JobLakeDeduplicationService svc = service(true);
        JobNormalized canonical = job("Acme Inc.", "Senior Backend Engineer", "Berlin", "c1");
        JobNormalized dup = job("Acme", "Senior Backend Engineer", "Berlin", "c2");
        when(normalized.findDedupCandidates("Germany", "Berlin"))
                .thenReturn(List.of(canonical))            // canonical's own pass
                .thenReturn(List.of(canonical, dup));      // dup's pass sees the canonical

        assertEquals(canonical.getId(), svc.deduplicate(canonical));
        UUID resolved = svc.deduplicate(dup);

        assertEquals(canonical.getId(), resolved);
        assertEquals(canonical.getId(), table.get(dup.getId()).getCanonicalJob());
    }

    @Test
    void identicalChecksumShortCircuitsToDuplicate() {
        JobLakeDeduplicationService svc = service(true);
        JobNormalized a = job("Acme", "Backend Engineer", "Berlin", "same");
        // Different company text, but identical checksum → must still be detected as the same posting.
        JobNormalized b = job("Totally Different Co", "Unrelated Title", "Berlin", "same");
        when(normalized.findDedupCandidates("Germany", "Berlin"))
                .thenReturn(List.of(a))
                .thenReturn(List.of(a, b));

        svc.deduplicate(a);
        assertEquals(a.getId(), svc.deduplicate(b));
    }

    @Test
    void alreadyMappedJobIsNotReprocessed() {
        JobLakeDeduplicationService svc = service(true);
        JobNormalized j = job("Acme", "Backend Engineer", "Berlin", "c1");
        UUID canonical = UUID.randomUUID();
        table.put(j.getId(), JobDuplicateMapping.builder().sourceJob(j.getId()).canonicalJob(canonical).build());

        assertEquals(canonical, svc.deduplicate(j));
        verify(normalized, never()).findDedupCandidates(any(), any());
    }
}
