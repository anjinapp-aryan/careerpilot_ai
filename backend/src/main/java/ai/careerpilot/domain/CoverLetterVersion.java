package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 2D.5 — one immutable, append-only cover letter generation (history behind {@link CoverLetter}). */
@Entity
@Table(name = "cover_letter_versions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CoverLetterVersion {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "cover_letter_id", nullable = false) private UUID coverLetterId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "resume_tailoring_id") private UUID resumeTailoringId;
    @Column(name = "candidate_profile_version") private UUID candidateProfileVersion;
    @Column(name = "behavior_profile_version") private Instant behaviorProfileVersion;

    @Column(nullable = false) private Integer version;
    private String provider;
    @Column(nullable = false) private String status;
    @Column(nullable = false, columnDefinition = "text") private String content;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
