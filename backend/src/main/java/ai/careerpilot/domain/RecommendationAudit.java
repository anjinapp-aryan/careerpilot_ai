package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable per-job scoring breakdown, written once per scored job on every
 * {@code JobMatchingService.refreshForUser} run (Phase 1.5). Mirrors
 * {@link ai.careerpilot.jobdiscovery.JobScoring.ScoreBreakdown} plus provenance
 * ({@code profileSource}: PROFILE | WORKFLOW | PREFERENCES, matching
 * {@link ai.careerpilot.jobdiscovery.CandidateSignalResolver}'s {@code source} field) so a future
 * explainability surface can answer "why did this job score X" without re-running the matcher.
 * Gated by {@code candidate.recommendation.audit-enabled}; purely additive — never read by any
 * existing matching/recommendation code path.
 */
@Entity
@Table(name = "recommendation_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RecommendationAudit {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    /** {@code CandidateProfileVersion.id} at the time of scoring, when the source was PROFILE. */
    @Column(name = "profile_version") private UUID profileVersion;
    @Column(name = "profile_source", nullable = false) private String profileSource;

    @Column(name = "skill_score", nullable = false) private int skillScore;
    @Column(name = "role_score", nullable = false) private int roleScore;
    @Column(name = "preference_score", nullable = false) private int preferenceScore;
    @Column(name = "location_score", nullable = false) private int locationScore;
    @Column(name = "visa_score", nullable = false) private int visaScore;
    @Column(name = "salary_score", nullable = false) private int salaryScore;
    @Column(name = "final_score", nullable = false) private int finalScore;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
