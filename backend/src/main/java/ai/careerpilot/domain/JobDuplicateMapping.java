package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps a duplicate normalized job to its canonical normalized job (Phase 2A, Step 6). The canonical
 * job self-references ({@code sourceJob == canonicalJob}) so a single query over this table yields
 * every member of a duplicate cluster. Unique on {@code source_job}: a job belongs to exactly one
 * canonical cluster. Written only when {@code JOB_DISCOVERY_DEDUP_ENABLED=true}.
 */
@Entity
@Table(name = "job_duplicate_mapping")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JobDuplicateMapping {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "source_job", nullable = false, unique = true) private UUID sourceJob;
    @Column(name = "canonical_job", nullable = false) private UUID canonicalJob;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
