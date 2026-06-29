package ai.careerpilot.repo;

import ai.careerpilot.domain.JobAiEnrichment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobAiEnrichmentRepository extends JpaRepository<JobAiEnrichment, UUID> {

    /** Upsert lookup — one enrichment row per job. */
    Optional<JobAiEnrichment> findByJobId(UUID jobId);

    /** Batch lookup for the matcher — enriched skills for a whole scored pool in one query. */
    List<JobAiEnrichment> findByJobIdIn(Collection<UUID> jobIds);

    // ── Admin Dashboard aggregations (Phase 2 Increment D) ───────────────────────

    /** Row shape for {@link #skillHeatmap}: a normalized skill and how many enriched jobs mention it. */
    interface SkillCount {
        String getSkill();
        Long getCnt();
    }

    /**
     * Top N normalized skills across all enriched jobs, most common first. Unnests the
     * {@code normalized_skills} JSONB array per row — fine at this pool size (hundreds of jobs);
     * would need a precomputed rollup table at 10k+ enriched jobs.
     */
    @Query(value = "SELECT skill AS skill, count(*) AS cnt FROM job_ai_enrichment, " +
                   "jsonb_array_elements_text(normalized_skills) AS skill " +
                   "WHERE normalized_skills IS NOT NULL " +
                   "GROUP BY skill ORDER BY cnt DESC LIMIT :limit", nativeQuery = true)
    List<SkillCount> skillHeatmap(@Param("limit") int limit);

    /** Row shape for {@link #salaryBySeniority}: an average salary band for one seniority+currency pair. */
    interface SalaryBand {
        String getSeniorityLevel();
        String getSalaryCurrency();
        java.math.BigDecimal getAvgMin();
        java.math.BigDecimal getAvgMax();
        Long getCnt();
    }

    /**
     * Average salary band grouped by (seniority, currency) — currencies are never averaged together,
     * since an EUR/USD mix would be meaningless. Only rows with both a min and a currency are included.
     */
    @Query(value = "SELECT seniority_level AS seniorityLevel, salary_currency AS salaryCurrency, " +
                   "avg(salary_band_min) AS avgMin, avg(salary_band_max) AS avgMax, count(*) AS cnt " +
                   "FROM job_ai_enrichment " +
                   "WHERE seniority_level IS NOT NULL AND salary_currency IS NOT NULL AND salary_band_min IS NOT NULL " +
                   "GROUP BY seniority_level, salary_currency ORDER BY seniority_level, salary_currency",
           nativeQuery = true)
    List<SalaryBand> salaryBySeniority();
}
