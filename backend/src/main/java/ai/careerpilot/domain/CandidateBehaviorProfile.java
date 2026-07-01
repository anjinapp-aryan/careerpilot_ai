package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2C-5 (Step 7) — deterministic behavioral summary derived from {@code recommendation_feedback}:
 * the roles/countries/work-modes/domains a user tends to accept (preferred_*) vs decline (rejected_*).
 * One row per user, comma-joined lists (mirrors {@code candidate_preferences}). Rebuilt on demand from
 * the feedback log, so it never drifts. Provisioned for future AI personalization; nothing consumes it
 * for matching yet.
 */
@Entity
@Table(name = "candidate_behavior_profile")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CandidateBehaviorProfile {

    @Id @Column(name = "user_id") private UUID userId;

    @Column(name = "preferred_roles", columnDefinition = "text") private String preferredRoles;
    @Column(name = "rejected_roles", columnDefinition = "text") private String rejectedRoles;
    @Column(name = "preferred_countries", columnDefinition = "text") private String preferredCountries;
    @Column(name = "rejected_countries", columnDefinition = "text") private String rejectedCountries;
    @Column(name = "preferred_work_modes", columnDefinition = "text") private String preferredWorkModes;
    @Column(name = "rejected_work_modes", columnDefinition = "text") private String rejectedWorkModes;
    @Column(name = "preferred_domains", columnDefinition = "text") private String preferredDomains;
    @Column(name = "rejected_domains", columnDefinition = "text") private String rejectedDomains;

    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
