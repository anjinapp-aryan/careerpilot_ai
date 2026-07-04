package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2D.3 — one deterministic (non-LLM) gap analysis of a tailored resume against its job:
 * what the job requires that the candidate's profile + resume + tailored resume do not evidence,
 * bucketed into skills / certifications / cloud / leadership / architecture / domains, plus a
 * 0–100 {@link #gapScore} (higher = bigger gap). Append-only; lists are comma-joined TEXT
 * (same CSV convention as {@code JobRecommendationExplanation}).
 */
@Entity
@Table(name = "resume_gap_analysis")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResumeGapAnalysis {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "resume_tailoring_id", nullable = false) private UUID resumeTailoringId;
    @Column(name = "resume_ats_analysis_id") private UUID resumeAtsAnalysisId;
    @Column(name = "candidate_profile_version") private UUID candidateProfileVersion;
    @Column(name = "behavior_profile_version") private Instant behaviorProfileVersion;

    @Column(name = "missing_skills", columnDefinition = "text") private String missingSkills;
    @Column(name = "missing_certifications", columnDefinition = "text") private String missingCertifications;
    @Column(name = "missing_cloud", columnDefinition = "text") private String missingCloud;
    @Column(name = "missing_leadership", columnDefinition = "text") private String missingLeadership;
    @Column(name = "missing_architecture", columnDefinition = "text") private String missingArchitecture;
    @Column(name = "missing_domains", columnDefinition = "text") private String missingDomains;

    @Column(name = "gap_score", nullable = false) private Integer gapScore;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
