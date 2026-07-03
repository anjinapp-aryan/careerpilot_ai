package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit row written on every {@link CandidateProfile} regeneration. Stores a
 * before/after JSON snapshot plus the trigger reason — backs audit, rollback, the
 * {@code /api/candidate-profile/history} endpoint, and future analytics. Never contains
 * resume text or PII beyond the structured profile fields.
 */
@Entity
@Table(name = "candidate_profile_versions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CandidateProfileVersion {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "profile_id") private UUID profileId;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "before", columnDefinition = "jsonb")
    private String beforeJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "after", columnDefinition = "jsonb")
    private String afterJson;

    /** RESUME_UPLOADED | RESUME_OPTIMIZED | PREFERENCES_UPDATED | MANUAL_REBUILD */
    @Column(name = "reason", nullable = false) private String reason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
