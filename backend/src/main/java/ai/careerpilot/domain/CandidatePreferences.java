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

    /**
     * Candidate's home country — the editable source; snapshotted onto {@code CandidateProfile}
     * on every regeneration. Domestic discovery resolves it via
     * {@code CandidateSignalResolver#resolveLocationSignals}, which reads this column directly
     * unless {@code candidate.profile.single-source-enabled} is on and a profile row exists.
     */
    @Column(name = "home_country") private String homeCountry;

    @Column(name = "preferred_countries", columnDefinition = "text") private String preferredCountries;
    @Column(name = "preferred_cities", columnDefinition = "text") private String preferredCities;
    @Column(name = "preferred_roles", columnDefinition = "text") private String preferredRoles;
    /** Comma-joined role/family names the user never wants recommended (e.g. "Sales,Marketing"). */
    @Column(name = "excluded_roles", columnDefinition = "text") private String excludedRoles;

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
