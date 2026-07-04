package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 2D.5 — append-only audit trail for cover-letter attempts, mirroring {@link ResumeTailoringAuditEntry}. */
@Entity
@Table(name = "cover_letter_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CoverLetterAuditEntry {

    public static final String OUTCOME_GENERATED = "GENERATED";
    public static final String OUTCOME_VALIDATION_REJECTED = "VALIDATION_REJECTED";
    public static final String OUTCOME_CACHE_HIT = "CACHE_HIT";
    public static final String OUTCOME_ERROR = "ERROR";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "cover_letter_id") private UUID coverLetterId;
    private Integer version;
    @Column(nullable = false) private String outcome;
    @Column(columnDefinition = "text") private String reason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
