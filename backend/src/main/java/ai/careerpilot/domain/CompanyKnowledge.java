package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7.13 — the HEAD knowledge row per (user, normalized company). {@code knowledge} is a JSON
 * section map (profile, culture, technology stack, hiring pattern, interview process, …) that only
 * ever grows from real platform artifacts — discovery rows, company research, interview prep,
 * learning outcomes — never from fabrication. Every revision is snapshotted immutably into
 * {@link CompanyKnowledgeVersion}; the 10 scores are deterministic and explained in
 * {@code scoreExplanations}.
 */
@Entity
@Table(name = "company_knowledge")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CompanyKnowledge {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "company_name", nullable = false) private String companyName;
    @Column(name = "normalized_name", nullable = false) private String normalizedName;
    @Column private String industry;

    @Column(columnDefinition = "text") private String knowledge; // JSON: section key -> content

    @Column(name = "quality_score") private Integer qualityScore;
    @Column(name = "interview_difficulty") private Integer interviewDifficulty;
    @Column(name = "hiring_probability") private Integer hiringProbability;
    @Column(name = "resume_compatibility") private Integer resumeCompatibility;
    @Column(name = "technology_match") private Integer technologyMatch;
    @Column(name = "career_growth") private Integer careerGrowth;
    @Column(name = "salary_potential") private Integer salaryPotential;
    @Column(name = "culture_match") private Integer cultureMatch;
    @Column(name = "learning_value") private Integer learningValue;
    @Column(name = "long_term_opportunity") private Integer longTermOpportunity;
    @Column(name = "score_explanations", columnDefinition = "text") private String scoreExplanations;

    @Column(name = "knowledge_version", nullable = false) private Integer knowledgeVersion;
    @Column(name = "last_source") private String lastSource;
    @Column(name = "first_discovered_at") private Instant firstDiscoveredAt;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
