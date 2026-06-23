package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Cached LLM output for the "Why am I a match?" explainability action. One row per
 * (user, job) — the only place in the job engine that calls an LLM, and it does so at most
 * once per pair (subsequent clicks read this cache). Lists are comma-joined TEXT.
 */
@Entity
@Table(name = "job_recommendation_explanation")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class JobRecommendationExplanation {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;

    @Column(name = "matching_skills", columnDefinition = "text") private String matchingSkills;
    @Column(name = "missing_skills", columnDefinition = "text") private String missingSkills;
    @Column(name = "resume_improvements", columnDefinition = "text") private String resumeImprovements;
    @Column(name = "ats_improvements", columnDefinition = "text") private String atsImprovements;
    @Column(name = "model_used") private String modelUsed;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
