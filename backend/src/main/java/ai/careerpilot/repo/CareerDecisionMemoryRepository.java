package ai.careerpilot.repo;

import ai.careerpilot.domain.CareerDecisionMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CareerDecisionMemoryRepository extends JpaRepository<CareerDecisionMemory, UUID> {

    /** Full timeline for a user, newest first — used by review area 7 (timeline), not by Copilot retrieval. */
    List<CareerDecisionMemory> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Candidate pool for ranked retrieval: unexpired memories for a user, narrowed to one category.
     * {@code CareerMemoryService} does the confidence/freshness/importance scoring and truncation
     * in Java — Postgres just filters out anything already expired or off-category.
     *
     * <p>Deliberately a {@code @Query}, not a derived method name: Spring Data parses "And"/"Or" in
     * derived names left-to-right with no grouping, so
     * {@code findByUserIdAndCategoryAndExpiresAtIsNullOrExpiresAtAfter} would actually evaluate as
     * {@code (userId AND category AND expiresAtIsNull) OR expiresAtAfter} — silently matching
     * unexpired rows for ANY user. Explicit JPQL avoids that trap.
     */
    @Query("SELECT m FROM CareerDecisionMemory m WHERE m.userId = :userId AND m.category = :category "
            + "AND (m.expiresAt IS NULL OR m.expiresAt > :now) ORDER BY m.createdAt DESC")
    List<CareerDecisionMemory> findActiveByUserIdAndCategory(
            @Param("userId") UUID userId, @Param("category") String category, @Param("now") Instant now);

    @Query("SELECT m FROM CareerDecisionMemory m WHERE m.userId = :userId "
            + "AND (m.expiresAt IS NULL OR m.expiresAt > :now) ORDER BY m.createdAt DESC")
    List<CareerDecisionMemory> findActiveByUserId(@Param("userId") UUID userId, @Param("now") Instant now);

    long countByUserId(UUID userId);

    /**
     * The most recent memory for (user, category) regardless of expiry — used by
     * {@code ConversationIntelligenceWorker} to decide "is this the same thing we already
     * remember" before writing a new row. Deliberately ignores {@code expiresAt}: an expired
     * memory about the same preference is still the right thing to compare freshness/value
     * against, not a reason to treat a repeated statement as brand new.
     */
    java.util.Optional<CareerDecisionMemory> findFirstByUserIdAndCategoryOrderByCreatedAtDesc(
            UUID userId, String category);

    /**
     * Bulk-bumps usage tracking for exactly the memories actually surfaced to the Copilot in one
     * turn — one UPDATE statement for the whole batch, not N. Called from
     * {@code CareerMemoryService.relevantFor} only when the result is non-empty, so a Copilot
     * turn that finds no relevant memory costs nothing extra.
     */
    @Modifying
    @Query("UPDATE CareerDecisionMemory m SET m.usageCount = m.usageCount + 1, m.lastUsedAt = :now "
            + "WHERE m.id IN :ids")
    void bumpUsage(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);

    /** Diagnostics: aggregate memory count per category, across all users — no per-user data. */
    @Query("SELECT m.category, COUNT(m) FROM CareerDecisionMemory m GROUP BY m.category")
    List<Object[]> countByCategory();

    /** Diagnostics: aggregate memory count per source (extraction origin), across all users. */
    @Query("SELECT m.source, COUNT(m) FROM CareerDecisionMemory m GROUP BY m.source")
    List<Object[]> countBySource();

    /** Diagnostics: average confidence across every memory — a coarse extraction-quality signal. */
    @Query("SELECT AVG(m.confidence) FROM CareerDecisionMemory m")
    Double averageConfidence();

    /** Diagnostics: how many memories were created after {@code cutoff} — one of three freshness buckets. */
    @Query("SELECT COUNT(m) FROM CareerDecisionMemory m WHERE m.createdAt > :cutoff")
    long countCreatedAfter(@Param("cutoff") Instant cutoff);

    /**
     * Diagnostics: the {@code limit} most-referenced (decisionType, value) pairs across all users
     * by usage_count — "top remembered preferences", aggregate only, no user identity attached.
     */
    @Query("SELECT m.decisionType, m.value, SUM(m.usageCount) AS uses FROM CareerDecisionMemory m "
            + "WHERE m.value IS NOT NULL GROUP BY m.decisionType, m.value ORDER BY uses DESC")
    List<Object[]> topRemembered(org.springframework.data.domain.Pageable page);
}
