package ai.careerpilot.repo;

import ai.careerpilot.domain.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /**
     * Server-authoritative Domestic/International read: discovered jobs whose country is in the
     * candidate-derived allow-list ({@code :countries}, already lower-cased), with the same optional
     * facets + search as {@link #findDiscoveredFiltered}. Both scopes funnel through this one query —
     * the scope only determines the country set (see {@code jobdiscovery.scope}). Unknown-country
     * jobs are excluded by construction (NULL country never matches IN), so they fall to Browse.
     * Callers must skip this when the country set is empty (SQL {@code IN ()} is invalid).
     */
    @Query(value = "SELECT * FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL " +
                   "AND LOWER(country) IN (:countries) " +
                   "AND (CAST(:remoteType AS text) IS NULL OR remote_type = CAST(:remoteType AS text)) " +
                   "AND (CAST(:sponsorship AS boolean) IS NULL OR sponsorship_available = CAST(:sponsorship AS boolean)) " +
                   "AND (CAST(:relocation AS boolean) IS NULL OR relocation_support = CAST(:relocation AS boolean)) " +
                   "AND (CAST(:q AS text) IS NULL OR LOWER(title) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) " +
                   "     OR LOWER(company) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) " +
                   "     OR LOWER(COALESCE(country,'')) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%'))) " +
                   "ORDER BY posted_date DESC NULLS LAST",
           countQuery = "SELECT COUNT(*) FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL " +
                   "AND LOWER(country) IN (:countries) " +
                   "AND (CAST(:remoteType AS text) IS NULL OR remote_type = CAST(:remoteType AS text)) " +
                   "AND (CAST(:sponsorship AS boolean) IS NULL OR sponsorship_available = CAST(:sponsorship AS boolean)) " +
                   "AND (CAST(:relocation AS boolean) IS NULL OR relocation_support = CAST(:relocation AS boolean)) " +
                   "AND (CAST(:q AS text) IS NULL OR LOWER(title) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) " +
                   "     OR LOWER(company) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')) " +
                   "     OR LOWER(COALESCE(country,'')) LIKE LOWER(CONCAT('%', CAST(:q AS text), '%')))",
           nativeQuery = true)
    Page<Job> findDiscoveredInCountries(@Param("countries") List<String> countries,
                                        @Param("remoteType") String remoteType,
                                        @Param("sponsorship") Boolean sponsorship,
                                        @Param("relocation") Boolean relocation,
                                        @Param("q") String q,
                                        Pageable pageable);

    /** Whole discovered pool, used by the rule-based matcher. */
    @Query(value = "SELECT * FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL " +
                   "ORDER BY posted_date DESC NULLS LAST LIMIT :limit", nativeQuery = true)
    List<Job> findDiscoveredPool(@Param("limit") int limit);

    // ── Admin Dashboard aggregations (Phase 2 Increment D) ───────────────────────

    @Query(value = "SELECT count(*) FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL", nativeQuery = true)
    long countDiscovered();

    @Query(value = "SELECT count(*) FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL " +
                   "AND embedding IS NOT NULL", nativeQuery = true)
    long countDiscoveredEmbedded();

    /** Row shape for {@link #countByCountry} / {@link #countBySource}: a group label and its count. */
    interface NamedCount {
        String getLabel();
        Long getCnt();
    }

    @Query(value = "SELECT COALESCE(country, 'Unknown') AS label, count(*) AS cnt FROM jobs " +
                   "WHERE org_id IS NULL AND external_id IS NOT NULL " +
                   "GROUP BY label ORDER BY cnt DESC LIMIT :limit", nativeQuery = true)
    List<NamedCount> countByCountry(@Param("limit") int limit);

    @Query(value = "SELECT source AS label, count(*) AS cnt FROM jobs " +
                   "WHERE org_id IS NULL AND external_id IS NOT NULL " +
                   "GROUP BY source ORDER BY cnt DESC", nativeQuery = true)
    List<NamedCount> countBySource();

    // ── Embeddings / pgvector semantic search (Phase 2 Increment A) ──────────────────
    // The vector(768) `embedding` column is not mapped on the Job entity (no pgvector Hibernate
    // type on the classpath), so it is written/read here via native SQL with a `::vector` cast.

    /** Newest discovered jobs that have no embedding yet — the work list for the capped embed pass. */
    @Query(value = "SELECT * FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL " +
                   "AND embedding IS NULL ORDER BY created_at DESC LIMIT :limit", nativeQuery = true)
    List<Job> findDiscoveredMissingEmbedding(@Param("limit") int limit);

    // ── Fuzzy deduplication (Phase 2 Increment C) ─────────────────────────────────────
    // Reuses the embeddings from Increment A as the primary duplicate-candidate signal (cosine
    // nearest-neighbor), confirmed with a title/company text check in DuplicateScoring.

    /** Newest embedded jobs with no duplicate-check row yet — the work list for the capped dedup pass. */
    @Query(value = "SELECT j.* FROM jobs j LEFT JOIN job_duplicates d ON d.job_id = j.id " +
                   "WHERE j.org_id IS NULL AND j.external_id IS NOT NULL AND j.embedding IS NOT NULL " +
                   "AND d.id IS NULL ORDER BY j.created_at DESC LIMIT :limit", nativeQuery = true)
    List<Job> findDiscoveredMissingDuplicateCheck(@Param("limit") int limit);

    /** A job's own embedding as a pgvector text literal (e.g. "[0.1,-0.2,...]"), for use as a query vector. */
    @Query(value = "SELECT embedding::text FROM jobs WHERE id = :id", nativeQuery = true)
    Optional<String> findEmbeddingVectorText(@Param("id") UUID id);

    /** Row shape for {@link #findNearestCrossSource}: a candidate job plus its cosine distance (0=identical). */
    interface DuplicateCandidate {
        UUID getId();
        String getTitle();
        String getCompany();
        Instant getCreatedAt();
        Double getDistance();
    }

    /**
     * Nearest embedded neighbor(s) to {@code vec}, excluding the job itself and anything from the
     * same source (same-source duplicates are already caught by the {@code (source, external_id)}
     * dedup key at ingest — this is purely for cross-source duplicate detection).
     */
    @Query(value = "SELECT id, title, company, created_at AS createdAt, " +
                   "embedding <=> CAST(:vec AS vector) AS distance " +
                   "FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL AND embedding IS NOT NULL " +
                   "AND id <> :excludeId AND source <> :excludeSource " +
                   "ORDER BY embedding <=> CAST(:vec AS vector) LIMIT :k", nativeQuery = true)
    List<DuplicateCandidate> findNearestCrossSource(@Param("vec") String vec,
                                                     @Param("excludeId") UUID excludeId,
                                                     @Param("excludeSource") String excludeSource,
                                                     @Param("k") int k);

    /** Set a job's embedding from a pgvector literal (e.g. "[0.1,-0.2,...]"). */
    @Modifying
    @Query(value = "UPDATE jobs SET embedding = CAST(:vec AS vector) WHERE id = :id", nativeQuery = true)
    void updateEmbedding(@Param("id") UUID id, @Param("vec") String vec);

    /** Cosine nearest-neighbor search over the embedded discovered pool (uses the HNSW index). */
    @Query(value = "SELECT * FROM jobs WHERE org_id IS NULL AND external_id IS NOT NULL " +
                   "AND embedding IS NOT NULL ORDER BY embedding <=> CAST(:vec AS vector) LIMIT :k",
           nativeQuery = true)
    List<Job> findNearestDiscovered(@Param("vec") String vec, @Param("k") int k);

    // ── LLM job enrichment (Phase 2 Increment B) ─────────────────────────────────────

    /**
     * Newest discovered jobs with no AI-enrichment row yet — the work list for the capped enrichment
     * pass. LEFT JOIN against the 1:1 {@code job_ai_enrichment} table; {@code e.id IS NULL} selects the
     * not-yet-enriched. Idempotent by construction: an enriched job drops out of this list.
     */
    @Query(value = "SELECT j.* FROM jobs j LEFT JOIN job_ai_enrichment e ON e.job_id = j.id " +
                   "WHERE j.org_id IS NULL AND j.external_id IS NOT NULL AND e.id IS NULL " +
                   "ORDER BY j.created_at DESC LIMIT :limit", nativeQuery = true)
    List<Job> findDiscoveredMissingEnrichment(@Param("limit") int limit);

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
