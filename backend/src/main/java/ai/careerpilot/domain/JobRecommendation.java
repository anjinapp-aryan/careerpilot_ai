package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted output of the rule-based matcher (Phase 2 Job Discovery). One row per
 * (user, job); {@code matching_skills}/{@code missing_skills} are comma-joined for
 * cheap storage and rendering. No LLM is involved in producing these.
 */
@Entity
@Table(name = "job_recommendations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JobRecommendation {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "resume_id") private UUID resumeId;
    @Column(name = "job_id", nullable = false) private UUID jobId;

    @Column(name = "match_score", nullable = false) private int matchScore;
    @Column(name = "matching_skills", columnDefinition = "text") private String matchingSkills;
    @Column(name = "missing_skills", columnDefinition = "text") private String missingSkills;
    @Column(name = "recommendation_reason", columnDefinition = "text") private String recommendationReason;

    // ── v2 scoring detail (additive; null on rows written by the legacy matcher) ──
    @Column(name = "score_breakdown", columnDefinition = "text") private String scoreBreakdown; // JSON of 6 factors
    @Column(name = "confidence_level") private String confidenceLevel; // HIGH | MEDIUM | LOW

    // ── Phase 2B-1 / 2C-2: action category (additive; null unless JOB_AUTO_CATEGORIZATION_ENABLED) ──
    @Column(name = "category") private String category; // AUTO_APPLY_READY | HIGH_PRIORITY | HUMAN_REVIEW | RECOMMENDED | ARCHIVED

    // ── Phase 2C-1: priority band + raw priority number (null unless RECOMMENDATION_PRIORITY_ENABLED) ──
    @Column(name = "priority") private String priority;             // CRITICAL | HIGH | MEDIUM | LOW
    @Column(name = "priority_score") private Integer priorityScore; // base match score + attribute bonuses

    // ── Phase 2C-2: MUST_APPLY flag (null unless JOB_AUTO_CATEGORIZATION_ENABLED) ──
    @Column(name = "must_apply") private Boolean mustApply;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
