package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 7.15 — one per-user rollup row, upserted after story mutations (cheap indexed read for dashboards). */
@Entity
@Table(name = "story_analytics")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StoryAnalytics {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true) private UUID userId;
    @Column(name = "total_stories", nullable = false) private Integer totalStories;
    @Column(name = "avg_quality_score") private Integer avgQualityScore;
    @Column(name = "by_category", columnDefinition = "text") private String byCategory; // JSON
    @Column(name = "by_competency", columnDefinition = "text") private String byCompetency; // JSON
    @Column(name = "usage_count", nullable = false) private Integer usageCount;
    @Column(name = "recommendation_count", nullable = false) private Integer recommendationCount;
    @Column(name = "last_computed_at") private Instant lastComputedAt;

    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
