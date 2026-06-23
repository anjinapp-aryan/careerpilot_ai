package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistent per-user job preferences that feed the recommendation scorer and the
 * Recommended/International facets. One row per user ({@code user_id} is the PK).
 * Location lists are comma-joined TEXT, mirroring {@code jobs.skills}.
 */
@Entity
@Table(name = "candidate_preferences")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CandidatePreferences {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "preferred_countries", columnDefinition = "text") private String preferredCountries;
    @Column(name = "preferred_cities", columnDefinition = "text") private String preferredCities;
    @Column(name = "preferred_roles", columnDefinition = "text") private String preferredRoles;

    @Column(name = "remote_preference", nullable = false) private boolean remotePreference;
    @Column(name = "hybrid_preference", nullable = false) private boolean hybridPreference;
    @Column(name = "onsite_preference", nullable = false) private boolean onsitePreference;
    @Column(name = "visa_sponsorship_required", nullable = false) private boolean visaSponsorshipRequired;
    @Column(name = "relocation_required", nullable = false) private boolean relocationRequired;

    @Column(name = "salary_expectation_min") private BigDecimal salaryExpectationMin;
    @Column(name = "salary_expectation_max") private BigDecimal salaryExpectationMax;
    @Column(name = "salary_currency") private String salaryCurrency;

    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
