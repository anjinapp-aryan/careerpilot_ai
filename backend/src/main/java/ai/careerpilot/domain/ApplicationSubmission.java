package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7.4 — one audit row per application-submission attempt made by the autonomous agent.
 * {@code status} is a {@code SubmissionStatus} name; with no credentialed provider integration
 * today every row is {@code HUMAN_REVIEW} (the agent never records a fabricated {@code SUBMITTED}).
 */
@Entity
@Table(name = "application_submission")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationSubmission {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column private String provider;
    @Column(nullable = false) private String status;
    @Column(name = "external_reference") private String externalReference;
    @Column(columnDefinition = "text") private String reason;
    @Column(name = "resume_tailoring_id") private UUID resumeTailoringId;
    @Builder.Default @Column(nullable = false) private int attempts = 1;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
