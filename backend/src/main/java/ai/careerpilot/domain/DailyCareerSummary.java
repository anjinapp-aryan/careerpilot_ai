package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Phase 5 — the daily AI briefing text for one user, generated at the end of a discovery run. */
@Entity
@Table(name = "daily_career_summary")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DailyCareerSummary {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "run_id", nullable = false) private UUID runId;
    @Column(name = "user_id", nullable = false) private UUID userId;

    @Column(name = "summary_text", columnDefinition = "text") private String summaryText;
    @Column(name = "jobs_fetched") private Integer jobsFetched;
    @Column(name = "jobs_deduped") private Integer jobsDeduped;
    @Column(name = "recommended_count") private Integer recommendedCount;
    @Column(name = "must_apply_count") private Integer mustApplyCount;
    @Column(name = "high_priority_count") private Integer highPriorityCount;
    @Column(name = "top_companies", columnDefinition = "text") private String topCompanies;
    @Column(name = "top_skills", columnDefinition = "text") private String topSkills;
    @Column(name = "interview_probability_delta") private BigDecimal interviewProbabilityDelta;
    @Column(name = "offer_probability_delta") private BigDecimal offerProbabilityDelta;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
