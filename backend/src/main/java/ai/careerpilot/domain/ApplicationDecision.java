package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7.1 — the autonomous agent's decision on one (user, job): AUTO_APPLY / HUMAN_REVIEW /
 * SAVE / IGNORE, computed deterministically from already-persisted recommendation, ATS, and
 * learning signals (see {@code ApplicationDecisionEngine}). Append-only; never mutates any
 * Phase 1–6 table.
 */
@Entity
@Table(name = "application_decision")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationDecision {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(nullable = false) private String outcome;   // DecisionOutcome name
    @Column(name = "match_score") private Integer matchScore;
    @Column(name = "ats_score") private Integer atsScore;
    @Column(name = "learning_boost") private Integer learningBoost;
    @Column(name = "must_apply") private Boolean mustApply;
    @Column private String priority;
    @Column(columnDefinition = "text") private String reason;

    @CreationTimestamp @Column(name = "decided_at", updatable = false) private Instant decidedAt;
}
