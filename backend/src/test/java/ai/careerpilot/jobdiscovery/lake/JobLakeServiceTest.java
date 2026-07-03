package ai.careerpilot.jobdiscovery.lake;

import ai.careerpilot.domain.JobLake;
import ai.careerpilot.repo.JobLakeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The lake status machine: create-at-DISCOVERED, forward-only advancement, and source-count update.
 * A backward or repeat transition must be a no-op so a re-run never regresses a job's stage.
 */
class JobLakeServiceTest {

    private JobLakeRepository repo;
    private JobLakeService service;
    private final Map<UUID, JobLake> table = new HashMap<>();

    @BeforeEach
    void setUp() {
        repo = mock(JobLakeRepository.class);
        table.clear();
        when(repo.findByNormalizedJobId(any())).thenAnswer(inv ->
                Optional.ofNullable(table.get((UUID) inv.getArgument(0))));
        when(repo.save(any(JobLake.class))).thenAnswer(inv -> {
            JobLake row = inv.getArgument(0);
            table.put(row.getNormalizedJobId(), row);
            return row;
        });
        service = new JobLakeService(repo);
    }

    @Test
    void upsertCreatesAtDiscoveredAndIsIdempotent() {
        UUID n = UUID.randomUUID();
        JobLake first = service.upsert(n);
        assertEquals(JobLake.STATUS_DISCOVERED, first.getStatus());
        assertEquals(1, first.getSourceCount());

        JobLake again = service.upsert(n);
        assertSame(first, again);
        verify(repo, times(1)).save(any(JobLake.class)); // only the first creates
    }

    @Test
    void advanceMovesForwardOnly() {
        UUID n = UUID.randomUUID();
        service.upsert(n);

        service.advanceTo(n, JobLake.STATUS_NORMALIZED);
        assertEquals(JobLake.STATUS_NORMALIZED, table.get(n).getStatus());

        service.advanceTo(n, JobLake.STATUS_READY_FOR_AI);
        assertEquals(JobLake.STATUS_READY_FOR_AI, table.get(n).getStatus());

        // Backward transition is ignored.
        service.advanceTo(n, JobLake.STATUS_DISCOVERED);
        assertEquals(JobLake.STATUS_READY_FOR_AI, table.get(n).getStatus());
    }

    @Test
    void setSourceCountClampsToAtLeastOne() {
        UUID n = UUID.randomUUID();
        service.upsert(n);
        service.setSourceCount(n, 5);
        assertEquals(5, table.get(n).getSourceCount());
        service.setSourceCount(n, 0);
        assertEquals(1, table.get(n).getSourceCount());
    }

    @Test
    void advanceOnUnknownJobIsNoOp() {
        assertDoesNotThrow(() -> service.advanceTo(UUID.randomUUID(), JobLake.STATUS_NORMALIZED));
    }
}
