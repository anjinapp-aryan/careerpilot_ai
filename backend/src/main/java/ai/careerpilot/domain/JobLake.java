package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Canonical Job Lake entry (Phase 2A, Step 7): one row per canonical normalized job, carrying its
 * position in the discovery lifecycle. {@code source_count} tracks how many provider listings
 * collapsed into this canonical job via {@code job_duplicate_mapping}.
 *
 * <p>The status machine advances strictly forward:
 * {@code DISCOVERED → NORMALIZED → DEDUPLICATED → READY_FOR_AI}. Phase 2A only fills the lake; the
 * READY_FOR_AI rows are the hand-off point for Phase 2B (AI enrichment / promotion into {@code jobs}).
 */
@Entity
@Table(name = "job_lake")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JobLake {

    /** Lifecycle status values; ordered to match the pipeline stages. */
    public static final String STATUS_DISCOVERED = "DISCOVERED";
    public static final String STATUS_NORMALIZED = "NORMALIZED";
    public static final String STATUS_DEDUPLICATED = "DEDUPLICATED";
    public static final String STATUS_READY_FOR_AI = "READY_FOR_AI";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "normalized_job_id", nullable = false, unique = true) private UUID normalizedJobId;

    @Column(nullable = false) private String status;
    @Column(name = "source_count", nullable = false) private int sourceCount;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
