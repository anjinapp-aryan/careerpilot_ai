package ai.careerpilot.repo;

import ai.careerpilot.domain.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    @Query(value = "SELECT j.* FROM jobs j WHERE j.org_id = :orgId AND (:q IS NULL " +
                   "OR LOWER(j.title) LIKE LOWER(CONCAT('%', :q, '%')) " +
                   "OR LOWER(j.company) LIKE LOWER(CONCAT('%', :q, '%')))",
           countQuery = "SELECT COUNT(*) FROM jobs j WHERE j.org_id = :orgId AND (:q IS NULL " +
                        "OR LOWER(j.title) LIKE LOWER(CONCAT('%', :q, '%')) " +
                        "OR LOWER(j.company) LIKE LOWER(CONCAT('%', :q, '%')))",
           nativeQuery = true)
    Page<Job> search(@Param("orgId") UUID orgId, @Param("q") String q, Pageable pageable);

    Optional<Job> findByIdAndOrgId(UUID id, UUID orgId);

    // ── Phase 2 Job Discovery: the global discovered pool (org_id IS NULL) ──────────

    /** Dedup lookup for upsert during ingest. */
    Optional<Job> findBySourceAndExternalId(String source, String externalId);

    /** Domestic tab: discovered jobs whose country matches (case-insensitive). */
    @Query(value = "SELECT * FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL " +
                   "AND LOWER(country) = LOWER(:country) ORDER BY posted_date DESC NULLS LAST",
           countQuery = "SELECT COUNT(*) FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL " +
                        "AND LOWER(country) = LOWER(:country)",
           nativeQuery = true)
    Page<Job> findDiscoveredByCountry(@Param("country") String country, Pageable pageable);

    /** International tab: discovered jobs whose country differs (or is unknown). */
    @Query(value = "SELECT * FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL " +
                   "AND (country IS NULL OR LOWER(country) <> LOWER(:country)) " +
                   "ORDER BY posted_date DESC NULLS LAST",
           countQuery = "SELECT COUNT(*) FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL " +
                        "AND (country IS NULL OR LOWER(country) <> LOWER(:country))",
           nativeQuery = true)
    Page<Job> findDiscoveredExcludingCountry(@Param("country") String country, Pageable pageable);

    /** Whole discovered pool, used by the rule-based matcher. */
    @Query(value = "SELECT * FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL " +
                   "ORDER BY posted_date DESC NULLS LAST LIMIT :limit", nativeQuery = true)
    List<Job> findDiscoveredPool(@Param("limit") int limit);

    /**
     * Unified Domestic/International read with optional facets + country search. Booleans/strings
     * are NULL-guarded (and cast) so omitting a facet means "don't filter on it". {@code scope}
     * = 'domestic' → country match; otherwise everything outside it (incl. unknown country).
     */
    @Query(value = "SELECT * FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL " +
                   "AND ( (LOWER(:scope) = 'domestic' AND LOWER(country) = LOWER(:country)) " +
                   "      OR (LOWER(:scope) <> 'domestic' AND (country IS NULL OR LOWER(country) <> LOWER(:country))) ) " +
                   "AND (CAST(:remoteType AS text) IS NULL OR remote_type = CAST(:remoteType AS text)) " +
                   "AND (CAST(:sponsorship AS boolean) IS NULL OR sponsorship_available = CAST(:sponsorship AS boolean)) " +
                   "AND (CAST(:relocation AS boolean) IS NULL OR relocation_support = CAST(:relocation AS boolean)) " +
                   "AND (CAST(:q AS text) IS NULL OR LOWER(title) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) " +
                   "     OR LOWER(company) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) " +
                   "     OR LOWER(COALESCE(country,'')) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%'))) " +
                   "ORDER BY posted_date DESC NULLS LAST",
           countQuery = "SELECT COUNT(*) FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL " +
                   "AND ( (LOWER(:scope) = 'domestic' AND LOWER(country) = LOWER(:country)) " +
                   "      OR (LOWER(:scope) <> 'domestic' AND (country IS NULL OR LOWER(country) <> LOWER(:country))) ) " +
                   "AND (CAST(:remoteType AS text) IS NULL OR remote_type = CAST(:remoteType AS text)) " +
                   "AND (CAST(:sponsorship AS boolean) IS NULL OR sponsorship_available = CAST(:sponsorship AS boolean)) " +
                   "AND (CAST(:relocation AS boolean) IS NULL OR relocation_support = CAST(:relocation AS boolean)) " +
                   "AND (CAST(:q AS text) IS NULL OR LOWER(title) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) " +
                   "     OR LOWER(company) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) " +
                   "     OR LOWER(COALESCE(country,'')) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')))",
           nativeQuery = true)
    Page<Job> findDiscoveredFiltered(@Param("scope") String scope,
                                     @Param("country") String country,
                                     @Param("remoteType") String remoteType,
                                     @Param("sponsorship") Boolean sponsorship,
                                     @Param("relocation") Boolean relocation,
                                     @Param("q") String q,
                                     Pageable pageable);

    /**
     * Browse "more opportunities": discovered jobs that did NOT clear the Recommended bar for
     * this user (i.e. not in their >= threshold recommendations). Implements the spec rule
     * "jobs below threshold must appear in Browse" with correct pagination.
     */
    @Query(value = "SELECT * FROM jobs j WHERE j.org_id IS NULL AND j.external_id IS NOT NULL " +
                   "AND j.id NOT IN (SELECT r.job_id FROM job_recommendations r " +
                   "                 WHERE r.user_id = :userId AND r.match_score >= :threshold) " +
                   "ORDER BY j.posted_date DESC NULLS LAST",
           countQuery = "SELECT COUNT(*) FROM jobs j WHERE j.org_id IS NULL AND j.external_id IS NOT NULL " +
                   "AND j.id NOT IN (SELECT r.job_id FROM job_recommendations r " +
                   "                 WHERE r.user_id = :userId AND r.match_score >= :threshold)",
           nativeQuery = true)
    Page<Job> findBrowsePool(@Param("userId") UUID userId, @Param("threshold") int threshold, Pageable pageable);
}
