package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2D.2 — one ATS-optimization analysis of a specific {@link ResumeTailoring} version:
 * a deterministic keyword-overlap score ({@link ai.careerpilot.resumetailoring.scoring.ResumeImprovementCalculator}),
 * plus LLM-generated matched/missing keywords and actionable optimization suggestions. Immutable
 * and append-only like {@code ResumeTailoring} itself — re-running analysis on the same tailoring
 * adds a new row rather than overwriting; "latest" reads order by {@link #createdAt} descending.
 */
@Entity
@Table(name = "resume_ats_analysis")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResumeAtsAnalysis {

    public static final String STATUS_GENERATED = "GENERATED";
    public static final String STATUS_ERROR = "ERROR";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "resume_tailoring_id", nullable = false) private UUID resumeTailoringId;

    @Column(name = "ats_score", nullable = false) private Integer atsScore;
    @Column(name = "matched_keywords", columnDefinition = "text") private String matchedKeywords;
    @Column(name = "missing_keywords", columnDefinition = "text") private String missingKeywords;
    @Column(columnDefinition = "text") private String suggestions;
    @Column(name = "confidence_score") private BigDecimal confidenceScore;
    @Column(name = "model_used") private String modelUsed;

    @Column(nullable = false) private String status;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
