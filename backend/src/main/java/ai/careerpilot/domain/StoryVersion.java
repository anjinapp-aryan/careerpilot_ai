package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7.15 — immutable append-only snapshot of one {@link StarStory} revision, mirroring
 * {@code CompanyKnowledgeVersion}. {@code snapshot} is a JSON dump of the full STAR field set at
 * that version. History is never rewritten; a rollback creates a NEW version.
 */
@Entity
@Table(name = "story_versions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StoryVersion {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "star_story_id", nullable = false) private UUID starStoryId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false) private Integer version;
    @Column(columnDefinition = "text") private String snapshot;
    @Column(name = "change_summary", columnDefinition = "text") private String changeSummary;
    @Column private String source; // StorySource name

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
