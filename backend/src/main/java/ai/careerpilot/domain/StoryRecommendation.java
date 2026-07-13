package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 7.15 — a persisted "best story for this company/role/question" recommendation. */
@Entity
@Table(name = "story_recommendations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StoryRecommendation {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "star_story_id", nullable = false) private UUID starStoryId;
    @Column(name = "company_name") private String companyName;
    @Column(name = "target_role") private String targetRole;
    @Column(columnDefinition = "text") private String question;
    @Column(name = "match_score") private Integer matchScore;
    @Column(columnDefinition = "text") private String reason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
