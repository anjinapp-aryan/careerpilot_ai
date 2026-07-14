package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 7.15 — one record of a story being used/suggested for a real interview context. */
@Entity
@Table(name = "story_usage")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StoryUsage {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "star_story_id", nullable = false) private UUID starStoryId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "company_name") private String companyName;
    @Column(name = "target_role") private String targetRole;
    @Column(name = "interview_round") private String interviewRound;
    @Column(columnDefinition = "text") private String question;
    @Column private String outcome; // e.g. ADVANCED, OFFER, REJECTED, UNKNOWN

    @CreationTimestamp @Column(name = "used_at", updatable = false) private Instant usedAt;
}
