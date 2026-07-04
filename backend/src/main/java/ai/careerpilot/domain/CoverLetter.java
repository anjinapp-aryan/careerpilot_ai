package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2D.5 — the HEAD cover letter row per (user, job): current version number + content,
 * rendered as "v1.N" at any future API layer. Every generation also appends an immutable
 * {@link CoverLetterVersion}; this row is just the always-current pointer/copy.
 */
@Entity
@Table(name = "cover_letter")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CoverLetter {

    public static final String STATUS_GENERATED = "GENERATED";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "application_id") private UUID applicationId;
    @Column(name = "resume_tailoring_id") private UUID resumeTailoringId;
    @Column(name = "candidate_profile_version") private UUID candidateProfileVersion;
    @Column(name = "behavior_profile_version") private Instant behaviorProfileVersion;

    @Column(nullable = false) private Integer version;
    private String provider;
    @Column(nullable = false) private String status;
    @Column(nullable = false, columnDefinition = "text") private String content;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
