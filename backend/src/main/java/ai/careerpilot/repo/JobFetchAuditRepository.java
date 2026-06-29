package ai.careerpilot.repo;

import ai.careerpilot.domain.JobFetchAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface JobFetchAuditRepository extends JpaRepository<JobFetchAudit, UUID> {

    List<JobFetchAudit> findTop20ByOrderByStartedAtDesc();

    // ── Admin Dashboard: discovery-provider health (Phase 2 Increment D) ─────────────
    // Distinct from AI-provider health (DeepSeek/Gemini/etc., already at GET /api/diagnostics/ai) —
    // these are the job-source providers (RemoteOK/Arbeitnow/Adzuna/Jooble).

    /** Most recent audit row per provider (one row per provider, latest run only). */
    @Query(value = "SELECT a.* FROM job_fetch_audit a WHERE a.id IN ( " +
                   "  SELECT DISTINCT ON (provider) id FROM job_fetch_audit " +
                   "  ORDER BY provider, started_at DESC" +
                   ") ORDER BY a.provider", nativeQuery = true)
    List<JobFetchAudit> findLatestPerProvider();

    /** Row shape for {@link #successRateByProvider}: success/total counts over recent runs. */
    interface ProviderRunStats {
        String getProvider();
        Long getSuccessCount();
        Long getTotalCount();
    }

    /** Success vs. total run counts per provider over the last 30 days — the health trend, not just the last run. */
    @Query(value = "SELECT provider AS provider, " +
                   "count(*) FILTER (WHERE status = 'SUCCESS') AS successCount, " +
                   "count(*) AS totalCount " +
                   "FROM job_fetch_audit WHERE started_at > now() - interval '30 days' " +
                   "GROUP BY provider", nativeQuery = true)
    List<ProviderRunStats> successRateByProvider();
}
