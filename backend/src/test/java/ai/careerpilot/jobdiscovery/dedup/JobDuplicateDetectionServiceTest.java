package ai.careerpilot.jobdiscovery.dedup;

import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobDuplicate;
import ai.careerpilot.repo.JobDuplicateRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.JobRepository.DuplicateCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Behavioral guards for the clustering logic — the part {@link DuplicateScoringTest} doesn't
 * cover. Verifies: a qualifying neighbor produces a cluster with the EARLIEST job as canonical,
 * joining an existing cluster reuses its canonical, a job with no qualifying neighbor still gets a
 * (self-canonical) row so it isn't re-checked every run, and a low text-similarity match is
 * rejected even when the embedding distance is excellent. Pure Mockito — no Spring context, no DB.
 */
class JobDuplicateDetectionServiceTest {

    private JobRepository jobs;
    private JobDuplicateRepository duplicates;
    private DuplicateScoring scoring;
    private JobDuplicateDetectionService service;

    /** In-memory job_duplicates table, keyed by job_id — lets save()/findByJobId() behave like the real repo. */
    private final Map<UUID, JobDuplicate> savedRows = new HashMap<>();

    @BeforeEach
    void setUp() {
        jobs = mock(JobRepository.class);
        duplicates = mock(JobDuplicateRepository.class);
        scoring = mock(DuplicateScoring.class);
        savedRows.clear();

        when(duplicates.findByJobId(any())).thenAnswer(inv -> Optional.ofNullable(savedRows.get(inv.getArgument(0))));
        when(duplicates.save(any())).thenAnswer(inv -> {
            JobDuplicate d = inv.getArgument(0);
            savedRows.put(d.getJobId(), d);
            return d;
        });

        service = new JobDuplicateDetectionService(jobs, duplicates, scoring, true, 200, 0.92, 0.55);
    }

    private Job job(UUID id, String title, String company, Instant createdAt, String source) {
        return Job.builder().id(id).title(title).company(company).createdAt(createdAt).source(source).build();
    }

    private DuplicateCandidate candidate(UUID id, String title, String company, Instant createdAt, double distance) {
        DuplicateCandidate c = mock(DuplicateCandidate.class);
        when(c.getId()).thenReturn(id);
        when(c.getTitle()).thenReturn(title);
        when(c.getCompany()).thenReturn(company);
        when(c.getCreatedAt()).thenReturn(createdAt);
        when(c.getDistance()).thenReturn(distance);
        return c;
    }

    @Test
    void disabledFlagIsNoOp() {
        var disabled = new JobDuplicateDetectionService(jobs, duplicates, scoring, false, 200, 0.92, 0.55);
        assertEquals(0, disabled.detectDuplicates());
        verifyNoInteractions(jobs);
    }

    @Test
    void qualifyingMatchCreatesClusterWithEarliestJobAsCanonical() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-02-01T00:00:00Z");
        UUID newerId = UUID.randomUUID();
        UUID olderNeighborId = UUID.randomUUID();

        Job newJob = job(newerId, "Senior Backend Engineer", "Stripe", newer, "remoteok");
        DuplicateCandidate neighbor = candidate(olderNeighborId, "Senior Backend Engineer", "Stripe", older, 0.03);
        when(jobs.findDiscoveredMissingDuplicateCheck(200)).thenReturn(List.of(newJob));
        when(jobs.findEmbeddingVectorText(newerId)).thenReturn(Optional.of("[0.1,0.2]"));
        when(jobs.findNearestCrossSource("[0.1,0.2]", newerId, "remoteok", 3)).thenReturn(List.of(neighbor));
        when(scoring.companyMatches("Stripe", "Stripe")).thenReturn(true);
        when(scoring.titleJaccard(any(), any())).thenReturn(1.0);

        int checked = service.detectDuplicates();

        assertEquals(1, checked);
        JobDuplicate newRow = savedRows.get(newerId);
        JobDuplicate neighborRow = savedRows.get(olderNeighborId);
        assertNotNull(newRow);
        assertNotNull(neighborRow);
        // The OLDER job (the neighbor) must be canonical, not the newly-discovered one.
        assertEquals(olderNeighborId, newRow.getCanonicalJobId());
        assertEquals(olderNeighborId, neighborRow.getCanonicalJobId());
        assertEquals(neighborRow.getDuplicateGroupId(), newRow.getDuplicateGroupId());
    }

    @Test
    void joiningAnExistingClusterReusesItsCanonical() {
        UUID jobId = UUID.randomUUID();
        UUID neighborId = UUID.randomUUID();
        UUID existingCanonical = UUID.randomUUID();
        UUID existingGroup = UUID.randomUUID();
        savedRows.put(neighborId, JobDuplicate.builder()
                .jobId(neighborId).canonicalJobId(existingCanonical).duplicateGroupId(existingGroup).build());

        Job newJob = job(jobId, "Product Manager", "Acme", Instant.now(), "remoteok");
        DuplicateCandidate neighbor = candidate(neighborId, "Product Manager", "Acme", Instant.now(), 0.02);
        when(jobs.findDiscoveredMissingDuplicateCheck(200)).thenReturn(List.of(newJob));
        when(jobs.findEmbeddingVectorText(jobId)).thenReturn(Optional.of("[0.5]"));
        when(jobs.findNearestCrossSource("[0.5]", jobId, "remoteok", 3)).thenReturn(List.of(neighbor));
        when(scoring.companyMatches(any(), any())).thenReturn(true);
        when(scoring.titleJaccard(any(), any())).thenReturn(1.0);

        service.detectDuplicates();

        // The new job must join the neighbor's EXISTING cluster, not invent a new one.
        assertEquals(existingCanonical, savedRows.get(jobId).getCanonicalJobId());
        assertEquals(existingGroup, savedRows.get(jobId).getDuplicateGroupId());
    }

    @Test
    void noQualifyingNeighborStillRecordsASelfCanonicalRow() {
        UUID jobId = UUID.randomUUID();
        Job newJob = job(jobId, "Marketing Coordinator", "Acme", Instant.now(), "remoteok");
        when(jobs.findDiscoveredMissingDuplicateCheck(200)).thenReturn(List.of(newJob));
        when(jobs.findEmbeddingVectorText(jobId)).thenReturn(Optional.of("[0.5]"));
        when(jobs.findNearestCrossSource(any(), any(), any(), anyInt())).thenReturn(List.of());

        int checked = service.detectDuplicates();

        assertEquals(1, checked);
        JobDuplicate row = savedRows.get(jobId);
        assertNotNull(row);
        assertEquals(jobId, row.getCanonicalJobId(), "an unmatched job must be its own canonical");
    }

    @Test
    void lowTitleSimilarityRejectsAnOtherwiseGoodEmbeddingMatch() {
        UUID jobId = UUID.randomUUID();
        UUID neighborId = UUID.randomUUID();
        Job newJob = job(jobId, "Senior Backend Engineer", "Acme", Instant.now(), "remoteok");
        // Excellent embedding similarity (distance 0.01) but a different company AND different title.
        DuplicateCandidate neighbor = candidate(neighborId, "Marketing Coordinator", "Globex", Instant.now(), 0.01);
        when(jobs.findDiscoveredMissingDuplicateCheck(200)).thenReturn(List.of(newJob));
        when(jobs.findEmbeddingVectorText(jobId)).thenReturn(Optional.of("[0.5]"));
        when(jobs.findNearestCrossSource(any(), any(), any(), anyInt())).thenReturn(List.of(neighbor));
        when(scoring.companyMatches("Acme", "Globex")).thenReturn(false);
        when(scoring.titleJaccard(any(), any())).thenReturn(0.1);

        service.detectDuplicates();

        // Rejected as a duplicate — falls through to the self-canonical path.
        assertEquals(jobId, savedRows.get(jobId).getCanonicalJobId());
        assertNull(savedRows.get(neighborId), "the rejected neighbor must not get a row from this job's pass");
    }

    @Test
    void jobWithNoEmbeddingYetIsSkippedNotFailed() {
        UUID jobId = UUID.randomUUID();
        Job newJob = job(jobId, "Engineer", "Acme", Instant.now(), "remoteok");
        when(jobs.findDiscoveredMissingDuplicateCheck(200)).thenReturn(List.of(newJob));
        when(jobs.findEmbeddingVectorText(jobId)).thenReturn(Optional.empty());

        int checked = service.detectDuplicates();

        assertEquals(1, checked, "isolated handling still counts as 'checked' even though no row was written");
        assertNull(savedRows.get(jobId));
    }
}
