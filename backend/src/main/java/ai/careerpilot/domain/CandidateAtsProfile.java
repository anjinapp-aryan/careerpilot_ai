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
 * Phase C — the user-owned facts a real employer application form asks for.
 *
 * <p><b>Deliberately not part of {@link CandidateProfile}.</b> That row is a derived snapshot:
 * {@code CandidateProfileService.upsert} rewrites every field it knows about from résumé-extracted
 * AI output and a {@code CandidatePreferences} snapshot on each rebuild. Hand-entered contact
 * details living there would survive only until someone added a line to that method. This table is
 * written exclusively by {@code CandidateAtsProfileService} and read by {@code FieldMappingService};
 * nothing in the profile-rebuild path touches it.
 *
 * <p><b>No fact is stored twice.</b> Years of experience, skills and seniority stay on
 * {@link CandidateProfile}; expected salary, home country and the visa-sponsorship search
 * preference stay on {@code CandidatePreferences}; name and email stay on {@link User}. This entity
 * holds only what had no home at all.
 *
 * <p>Every column is nullable. A profile is completed progressively, and
 * {@code ProfileCompletenessService} exists precisely to describe how far along it is — an
 * incomplete row is the normal state, not an error.
 */
@Entity
@Table(name = "candidate_ats_profile")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CandidateAtsProfile {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;

    // ── Contact ──
    @Column(name = "phone") private String phone;
    @Column(name = "address_line1") private String addressLine1;
    @Column(name = "address_line2") private String addressLine2;
    @Column(name = "city") private String city;
    @Column(name = "state_province") private String stateProvince;
    @Column(name = "postal_code") private String postalCode;
    @Column(name = "country") private String country;

    // ── Professional links ──
    @Column(name = "linkedin_url") private String linkedinUrl;
    @Column(name = "github_url") private String githubUrl;
    @Column(name = "portfolio_url") private String portfolioUrl;
    @Column(name = "personal_website_url") private String personalWebsiteUrl;

    // ── Current employment ──
    @Column(name = "current_company") private String currentCompany;
    @Column(name = "current_title") private String currentTitle;
    @Column(name = "notice_period") private String noticePeriod;
    @Column(name = "current_salary") private BigDecimal currentSalary;
    @Column(name = "current_salary_currency") private String currentSalaryCurrency;

    // ── Education ──
    @Column(name = "highest_education") private String highestEducation;
    @Column(name = "degree") private String degree;
    @Column(name = "field_of_study") private String fieldOfStudy;
    @Column(name = "university") private String university;
    @Column(name = "graduation_year") private Integer graduationYear;

    // ── Work authorisation ──
    @Column(name = "work_authorization") private String workAuthorization;
    @Column(name = "visa_status") private String visaStatus;
    @Column(name = "citizenship") private String citizenship;
    @Column(name = "security_clearance") private String securityClearance;

    // ── Lists ──
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "languages_json")
    private String languagesJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "certifications_json")
    private String certificationsJson;

    /**
     * fieldName → {@link FieldVerificationSource} name. A field absent from this map is treated as
     * unverified rather than trusted — see {@code CandidateAtsProfileService.sourceOf}.
     */
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "field_sources")
    private String fieldSourcesJson;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
