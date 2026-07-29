package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Mission Engine, Phase 1 — soft career-preference signals, one row per user. <b>Deliberately
 * NOT a duplicate of {@code candidate_preferences}</b> (V5/V9/V10 migrations, backing {@code
 * CandidatePreferences}): location/salary/visa/remote-mode preferences already live there and are
 * consumed by job matching — this table holds only preference dimensions that table does not
 * (risk tolerance, company-stage fit, work-culture priority, mentorship). No dedicated REST
 * surface in Phase 1, same rationale as {@link CareerGoal}.
 */
@Entity
@Table(name = "user_career_preference")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserCareerPreference {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true) private UUID userId;

    /** LOW | MEDIUM | HIGH — free string, not an enum, so a Phase-2 refinement of the bands is a data change. */
    @Column(name = "risk_tolerance") private String riskTolerance;
    /** STARTUP | SCALEUP | ENTERPRISE — same free-string rationale. */
    @Column(name = "preferred_company_stage") private String preferredCompanyStage;
    @Column(name = "work_culture_priority") private String workCulturePriority;

    @Column(name = "willing_to_relocate", nullable = false) @Builder.Default private Boolean willingToRelocate = true;
    @Column(name = "open_to_contract_roles", nullable = false) @Builder.Default private Boolean openToContractRoles = false;
    @Column(name = "mentorship_preference") private String mentorshipPreference;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
