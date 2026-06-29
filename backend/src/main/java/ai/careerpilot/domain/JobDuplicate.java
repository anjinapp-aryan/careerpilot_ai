package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Fuzzy-duplicate-detection result for a discovered job (Phase 2 Increment C). One row per
 * duplicate-checked job — including the canonical member of a cluster, which self-references
 * ({@code canonicalJobId == jobId}). All jobs sharing a {@code duplicateGroupId} are considered
 * the same underlying posting, detected via embedding cosine similarity + title/company text
 * confirmation (see {@code DuplicateScoring}).
 *
 * <p>Separate table, not columns on {@code jobs} — for the same reason as {@link JobAiEnrichment}:
 * the daily {@code JobNormalizer.merge()} re-upsert only rewrites {@code jobs} columns, so this
 * survives a re-fetch. Produced-but-not-consumed in v1 (listings don't filter on this yet).
 */
@Entity
@Table(name = "job_duplicates")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JobDuplicate {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "job_id", nullable = false, unique = true) private UUID jobId;
    @Column(name = "canonical_job_id", nullable = false) private UUID canonicalJobId;
    @Column(name = "duplicate_group_id", nullable = false) private UUID duplicateGroupId;
    @Column(name = "similarity_score") private BigDecimal similarityScore;
    @Column(name = "match_signals", columnDefinition = "text") private String matchSignals;

    @CreationTimestamp @Column(name = "detected_at", updatable = false) private Instant detectedAt;
}
