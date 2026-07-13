package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7.15 — the HEAD row of one candidate STAR story. Situation/Task/Action/Result plus
 * Reflection/Lessons Learned/Skills/Technologies/Competencies/Business Impact/Evidence are modeled
 * directly as columns (not a separate section-per-row table) — the same "current head + immutable
 * version snapshots" shape as {@code CompanyKnowledge}/{@code CompanyKnowledgeVersion}. Every
 * revision is snapshotted immutably into {@link StoryVersion}.
 */
@Entity
@Table(name = "star_stories")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StarStory {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(nullable = false) private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "story_type", nullable = false, length = 64)
    private ai.careerpilot.story.StoryType storyType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ai.careerpilot.story.StoryStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ai.careerpilot.story.StorySource source;

    @Column(name = "source_ref_id") private UUID sourceRefId;

    @Column(columnDefinition = "text") private String situation;
    @Column(columnDefinition = "text") private String task;
    @Column(columnDefinition = "text") private String action;
    @Column(columnDefinition = "text") private String result;
    @Column(columnDefinition = "text") private String reflection;
    @Column(name = "lessons_learned", columnDefinition = "text") private String lessonsLearned;
    @Column(name = "skills_used", columnDefinition = "text") private String skillsUsed;
    @Column(name = "technologies_used", columnDefinition = "text") private String technologiesUsed;
    @Column(columnDefinition = "text") private String competencies; // comma-separated BehavioralCompetency names
    @Column(name = "business_impact", columnDefinition = "text") private String businessImpact;
    @Column(columnDefinition = "text") private String evidence;

    @Column(name = "confidence_score") private Integer confidenceScore;
    @Column(name = "quality_score") private Integer qualityScore;
    @Column(name = "quality_breakdown", columnDefinition = "text") private String qualityBreakdown; // JSON
    @Column(name = "improvement_suggestions", columnDefinition = "text") private String improvementSuggestions;
    @Column(name = "missing_sections", columnDefinition = "text") private String missingSections;

    @Column(name = "current_version", nullable = false) private Integer currentVersion;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
