package ai.careerpilot.jobdiscovery.lake;

import ai.careerpilot.domain.JobLake;
import ai.careerpilot.repo.JobLakeRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Owns the canonical Job Lake entry and its lifecycle (Phase 2A Step 7). Status advances strictly
 * forward through {@code DISCOVERED → NORMALIZED → DEDUPLICATED → READY_FOR_AI}; a backward or
 * sideways transition is silently ignored so a re-run never regresses a job's stage.
 *
 * <p>Phase 2A only fills the lake. The {@code READY_FOR_AI} rows are the hand-off point for Phase 2B
 * (AI enrichment / promotion into {@code jobs}); nothing downstream consumes them yet.
 */
@Service
public class JobLakeService {

    /** Stage order; index used to enforce forward-only transitions. */
    private static final List<String> ORDER = List.of(
            JobLake.STATUS_DISCOVERED, JobLake.STATUS_NORMALIZED,
            JobLake.STATUS_DEDUPLICATED, JobLake.STATUS_READY_FOR_AI);

    private final JobLakeRepository repo;

    public JobLakeService(JobLakeRepository repo) {
        this.repo = repo;
    }

    /** Create the lake entry for a normalized job (status DISCOVERED) if absent; idempotent. */
    public JobLake upsert(UUID normalizedJobId) {
        return repo.findByNormalizedJobId(normalizedJobId).orElseGet(() ->
                repo.save(JobLake.builder()
                        .normalizedJobId(normalizedJobId)
                        .status(JobLake.STATUS_DISCOVERED)
                        .sourceCount(1)
                        .build()));
    }

    /** Advance a lake entry forward to {@code target}; no-op if it's already at/past that stage. */
    public void advanceTo(UUID normalizedJobId, String target) {
        repo.findByNormalizedJobId(normalizedJobId).ifPresent(row -> {
            if (isForward(row.getStatus(), target)) {
                row.setStatus(target);
                repo.save(row);
            }
        });
    }

    /** Record how many provider listings collapsed into this canonical job (dedup cluster size). */
    public void setSourceCount(UUID normalizedJobId, int sourceCount) {
        repo.findByNormalizedJobId(normalizedJobId).ifPresent(row -> {
            row.setSourceCount(Math.max(1, sourceCount));
            repo.save(row);
        });
    }

    private static boolean isForward(String current, String target) {
        int c = ORDER.indexOf(current);
        int t = ORDER.indexOf(target);
        return t > c && t >= 0;
    }
}
