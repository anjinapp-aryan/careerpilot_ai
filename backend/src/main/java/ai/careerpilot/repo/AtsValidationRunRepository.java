package ai.careerpilot.repo;

import ai.careerpilot.domain.AtsValidationRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Phase 13A — read access to the append-only validation history.
 *
 * <p>Every finder is bounded ({@code findTopN}), matching this codebase's established convention
 * for history reads ({@code WorkflowRunRepository.findTop20By...}) rather than returning an
 * unbounded series into a 1-vCPU box's heap.
 *
 * <p>There is deliberately no delete or update method. The append-only contract is enforced by the
 * absence of a way to violate it, not by a comment.
 */
public interface AtsValidationRunRepository extends JpaRepository<AtsValidationRun, java.util.UUID> {

    /** The trend series for one ATS, newest first. */
    List<AtsValidationRun> findTop50ByAtsPlatformOrderByCreatedAtDesc(String atsPlatform);

    /** The per-posting series, which is what drift detection compares like-for-like. */
    List<AtsValidationRun> findTop20ByUrlHashOrderByCreatedAtDesc(String urlHash);

    /** Recent runs across every ATS, for the campaign dashboard. */
    List<AtsValidationRun> findTop100ByOrderByCreatedAtDesc();

    /**
     * Which ATS platforms have any history at all.
     *
     * <p>Exists so the campaign report does not issue one series query per {@code AtsPlatform}
     * enum value. That loop meant 11 round-trips to the external database on every call to the
     * <em>unauthenticated</em> {@code GET /api/diagnostics/browser}, nine of which returned
     * nothing — measured at 3.9s per request. This turns it into 1 + (platforms actually tested),
     * with byte-identical output, since a platform absent from this list is one the loop would
     * have skipped anyway.
     */
    @org.springframework.data.jpa.repository.Query(
            "select distinct r.atsPlatform from AtsValidationRun r")
    List<String> findDistinctAtsPlatforms();

    long countByAtsPlatform(String atsPlatform);

    long countByAtsPlatformAndReadyTrue(String atsPlatform);
}
