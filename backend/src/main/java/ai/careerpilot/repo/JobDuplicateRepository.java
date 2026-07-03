package ai.careerpilot.repo;

import ai.careerpilot.domain.JobDuplicate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobDuplicateRepository extends JpaRepository<JobDuplicate, UUID> {

    /** Lookup for both "is this job already checked" and "what cluster is this neighbor in". */
    Optional<JobDuplicate> findByJobId(UUID jobId);

    // ── Admin Dashboard aggregations ──────────────────────────────────────────────

    /** Row shape for {@link #clusterSummary}: one duplicate cluster and its member count. */
    interface ClusterSummary {
        UUID getCanonicalJobId();
        String getCanonicalTitle();
        String getCanonicalCompany();
        Long getMemberCount();
    }

    /**
     * Clusters with more than one member (i.e. actual duplicates found), largest first.
     * Joins back to {@code jobs} once for the canonical's title/company for display.
     */
    @Query(value = "SELECT d.canonical_job_id AS canonicalJobId, j.title AS canonicalTitle, " +
                   "j.company AS canonicalCompany, count(*) AS memberCount " +
                   "FROM job_duplicates d JOIN jobs j ON j.id = d.canonical_job_id " +
                   "GROUP BY d.canonical_job_id, j.title, j.company " +
                   "HAVING count(*) > 1 ORDER BY memberCount DESC LIMIT :limit", nativeQuery = true)
    List<ClusterSummary> clusterSummary(int limit);

    @Query(value = "SELECT count(DISTINCT duplicate_group_id) FROM job_duplicates d " +
                   "WHERE EXISTS (SELECT 1 FROM job_duplicates d2 WHERE d2.duplicate_group_id = d.duplicate_group_id " +
                   "GROUP BY d2.duplicate_group_id HAVING count(*) > 1)", nativeQuery = true)
    long countDuplicateGroups();

    /** Non-canonical rows = jobs that are duplicates of something else (the "clutter" count). */
    @Query(value = "SELECT count(*) FROM job_duplicates WHERE job_id <> canonical_job_id", nativeQuery = true)
    long countNonCanonicalDuplicates();
}
