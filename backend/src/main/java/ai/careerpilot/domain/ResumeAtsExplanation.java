package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2D.4 — deterministic explanation of an ATS score: which required signals matched
 * (✓ per category) and which are missing (✗), with a confidence figure and concrete
 * recommendations. Derived arithmetically from {@code ResumeAtsAnalysis} + {@code
 * ResumeGapAnalysis} rows — no LLM scoring. Append-only; lists are comma-joined TEXT.
 */
@Entity
@Table(name = "resume_ats_explanation")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResumeAtsExplanation {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "resume_tailoring_id", nullable = false) private UUID resumeTailoringId;
    @Column(name = "resume_ats_analysis_id") private UUID resumeAtsAnalysisId;
    @Column(name = "gap_analysis_id") private UUID gapAnalysisId;

    @Column(name = "ats_score") private Integer atsScore;
    @Column(name = "matched_skills", columnDefinition = "text") private String matchedSkills;
    @Column(name = "matched_experience", columnDefinition = "text") private String matchedExperience;
    @Column(name = "matched_cloud", columnDefinition = "text") private String matchedCloud;
    @Column(name = "matched_leadership", columnDefinition = "text") private String matchedLeadership;
    @Column(name = "matched_architecture", columnDefinition = "text") private String matchedArchitecture;
    @Column(name = "missing_items", columnDefinition = "text") private String missingItems;
    private BigDecimal confidence;
    @Column(columnDefinition = "text") private String recommendations;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
