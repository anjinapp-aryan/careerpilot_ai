package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2C-4 (Step 6) — one explicit user reaction to a recommendation (approve/reject/ignore/save/
 * apply-later) plus an optional reason. Append-only; feeds {@code candidate_behavior_profile} (2C-5).
 * Written only when {@code recommendation.feedback.enabled} is on.
 */
@Entity
@Table(name = "recommendation_feedback")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RecommendationFeedback {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(nullable = false) private String action; // APPROVE | REJECT | IGNORE | SAVE | APPLY_LATER
    @Column(columnDefinition = "text") private String reason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
