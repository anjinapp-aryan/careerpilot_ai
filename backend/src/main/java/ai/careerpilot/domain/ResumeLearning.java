package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 6.5 — learned performance for one (user, resumeVersion). {@code resumeVersion} is a proxy
 * identifier (the originating {@code resume_tailoring_jobs.resume_tailoring_id} as text), since
 * neither {@code ResumeTailoringJob} nor {@code ResumeAtsAnalysis} carries a human version string.
 */
@Entity
@Table(name = "resume_learning")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResumeLearning {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "resume_version", nullable = false) private String resumeVersion;
    @Builder.Default @Column(nullable = false) private int applications = 0;
    @Builder.Default @Column(nullable = false) private int interviews = 0;
    @Builder.Default @Column(nullable = false) private int offers = 0;
    @Column(name = "ats_score_avg") private BigDecimal atsScoreAvg;
    @Column(name = "interview_rate") private BigDecimal interviewRate;
    @Column(name = "offer_rate") private BigDecimal offerRate;
    @Builder.Default @Column(name = "is_best_version", nullable = false) private boolean bestVersion = false;
    @Column(name = "computed_at") private Instant computedAt;
}
