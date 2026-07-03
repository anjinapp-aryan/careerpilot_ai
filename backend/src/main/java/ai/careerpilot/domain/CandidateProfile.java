package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Canonical, AI-analyzed candidate record (Phase 1 — Candidate Intelligence Profile).
 * One row per user. Merges resume-derived intelligence (cached, re-extracted only when the
 * resume changes — see {@code resumeFingerprint}) with a snapshot of the editable
 * {@link CandidatePreferences}. Stores structured intelligence only — never resume text.
 *
 * All list-shaped fields are JSONB on the wire and persisted as JSON strings via
 * {@code @JdbcTypeCode(SqlTypes.JSON)}, mirroring {@link Resume#getCandidateProfileJson()}.
 * Gated by {@code CANDIDATE_PROFILE_ENABLED}. Consumed by job matching/recommendation via
 * {@code CandidateSignalResolver} when {@code JOBS_MATCHING_PROFILE_SOURCE_ENABLED} is on, and by
 * Domestic/International discovery + excluded-role filtering when
 * {@code PROFILE_SINGLE_SOURCE_ENABLED} is on (Phase 1.5) — both flag-gated, both fall back to
 * the legacy {@code CandidatePreferences}/{@code WorkflowRun} sources when off or no profile row
 * exists yet.
 */
@Entity
@Table(name = "candidate_profiles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CandidateProfile {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "resume_id") private UUID resumeId;

    // ── AI-derived from the resume ──────────────────────────────────────────────
    @Column(name = "years_experience") private Integer yearsExperience;
    @Column(name = "current_role_title") private String currentRole;
    @Column(name = "seniority_level") private String seniorityLevel;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "target_roles", columnDefinition = "jsonb")
    private String targetRolesJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "skills", columnDefinition = "jsonb")
    private String skillsJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "domains", columnDefinition = "jsonb")
    private String domainsJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "languages", columnDefinition = "jsonb")
    private String languagesJson;

    @Column(name = "profile_summary", columnDefinition = "text") private String profileSummary;
    @Column(name = "confidence_score") private BigDecimal confidenceScore;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "technologies", columnDefinition = "jsonb")
    private String technologiesJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "certifications", columnDefinition = "jsonb")
    private String certificationsJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "industries", columnDefinition = "jsonb")
    private String industriesJson;
    @Column(name = "leadership_experience") private Boolean leadershipExperience;
    @Column(name = "cloud_expertise") private Boolean cloudExpertise;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "career_goals", columnDefinition = "jsonb")
    private String careerGoalsJson;

    // ── Snapshot of candidate_preferences (editable source lives in that table) ──
    /** Snapshot of the candidate's home country — source of truth for the Domestic discovery tab. */
    @Column(name = "home_country") private String homeCountry;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "preferred_countries", columnDefinition = "jsonb")
    private String preferredCountriesJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "preferred_cities", columnDefinition = "jsonb")
    private String preferredCitiesJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "work_modes", columnDefinition = "jsonb")
    private String workModesJson;

    @Column(name = "visa_required") private Boolean visaRequired;
    @Column(name = "salary_currency") private String salaryCurrency;
    @Column(name = "salary_min") private BigDecimal salaryMin;
    @Column(name = "salary_target") private BigDecimal salaryTarget;

    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "excluded_roles", columnDefinition = "jsonb")
    private String excludedRolesJson;

    /** SHA-256 of the resume parsed_text the AI extraction was derived from. */
    @Column(name = "resume_fingerprint") private String resumeFingerprint;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
