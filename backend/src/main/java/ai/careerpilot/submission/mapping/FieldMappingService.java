package ai.careerpilot.submission.mapping;

import ai.careerpilot.domain.CandidateAtsProfile;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.User;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.service.profile.JsonLists;
import ai.careerpilot.service.profile.ats.AtsProfileField;
import ai.careerpilot.service.profile.ats.CandidateAtsProfileService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.16 — maps what is genuinely derivable from {@link User}/{@link CandidateProfile} into
 * the canonical application-form field set, and explicitly marks the rest {@code unmapped}.
 *
 * <p><b>Data-source honesty.</b> A field this platform cannot answer is reported {@code unmapped}
 * with a {@code null} value — never a placeholder, an empty string, or a plausible guess.
 *
 * <p><b>Phase C changed what is answerable, not that rule.</b> Phone, LinkedIn, GitHub and
 * portfolio previously had no column anywhere in the schema and were permanently unmapped; they are
 * now backed by {@code candidate_ats_profile}, along with location, employment, education and
 * work-authorisation fields. Two properties of that expansion matter here:
 * <ul>
 *   <li><b>Provenance is enforced.</b> Values arrive through
 *       {@code CandidateAtsProfileService.trustedValue}, so an unreviewed {@code AI_SUGGESTED}
 *       value maps as {@code unmapped} exactly like an absent one. This result feeds the form
 *       filler; an unconfirmed guess must never reach an employer.</li>
 *   <li><b>Nothing is duplicated.</b> Facts already owned elsewhere — name and email on
 *       {@code User}, years of experience and skills on {@code CandidateProfile}, expected salary
 *       and home country from the preference snapshot — are still read from their existing owner.</li>
 * </ul>
 * With {@code candidate.ats-profile.enabled} off, every Phase C field maps as {@code unmapped} and
 * this service behaves exactly as it did before the phase.
 */
@Service
public class FieldMappingService {

    private final UserRepository users;
    private final CandidateProfileRepository profiles;
    private final CandidateAtsProfileService atsProfiles;

    public FieldMappingService(UserRepository users, CandidateProfileRepository profiles,
                               CandidateAtsProfileService atsProfiles) {
        this.users = users;
        this.profiles = profiles;
        this.atsProfiles = atsProfiles;
    }

    public FieldMappingResult map(UUID userId) {
        List<MappedField> fields = new ArrayList<>();
        Optional<User> user = users.findById(userId);
        Optional<CandidateProfile> profile = profiles.findByUserId(userId);

        fields.add(user.map(u -> MappedField.mapped("fullName", u.getFullName(), "User.fullName"))
                .orElse(MappedField.unmapped("fullName")));
        fields.add(user.map(u -> MappedField.mapped("email", u.getEmail(), "User.email"))
                .orElse(MappedField.unmapped("email")));

        fields.add(profile.filter(p -> p.getYearsExperience() != null)
                .map(p -> MappedField.mapped("yearsExperience", String.valueOf(p.getYearsExperience()),
                        "CandidateProfile.yearsExperience"))
                .orElse(MappedField.unmapped("yearsExperience")));

        fields.add(profile.map(CandidateProfile::getSkillsJson).filter(s -> s != null && !s.isBlank())
                .map(s -> MappedField.mapped("skills", String.join(", ", JsonLists.toList(s)),
                        "CandidateProfile.skills"))
                .orElse(MappedField.unmapped("skills")));

        fields.add(profile.filter(p -> p.getVisaRequired() != null)
                .map(p -> MappedField.mapped("visaRequired", String.valueOf(p.getVisaRequired()),
                        "CandidateProfile.visaRequired"))
                .orElse(MappedField.unmapped("visaRequired")));

        fields.add(profile.filter(p -> p.getSalaryTarget() != null)
                .map(p -> MappedField.mapped("salaryTarget", p.getSalaryTarget().toString(),
                        "CandidateProfile.salaryTarget"))
                .orElse(MappedField.unmapped("salaryTarget")));

        fields.add(profile.filter(p -> p.getHomeCountry() != null && !p.getHomeCountry().isBlank())
                .map(p -> MappedField.mapped("location", p.getHomeCountry(), "CandidateProfile.homeCountry"))
                .orElse(MappedField.unmapped("location")));

        // ── Phase C: everything below is backed by candidate_ats_profile ──
        //
        // Only values whose recorded provenance is trusted for automation are mapped. An unreviewed
        // AI suggestion reads back as unmapped here, which is the same outcome as having no value —
        // deliberately, since this result feeds the form filler and an unconfirmed guess must never
        // reach an employer.
        Optional<CandidateAtsProfile> ats = atsProfiles.get(userId);
        for (AtsProfileField field : AtsProfileField.values()) {
            fields.add(ats.flatMap(p -> atsProfiles.trustedValue(p, field))
                    .map(v -> MappedField.mapped(field.fieldName(), v,
                            "CandidateAtsProfile." + field.fieldName()))
                    .orElse(MappedField.unmapped(field.fieldName())));
        }

        // Legacy aliases. Pre-Phase-C callers ask for these exact names, so they keep resolving —
        // now with real values behind them instead of a permanent unmapped result.
        fields.add(alias(ats, AtsProfileField.LINKEDIN_URL, "linkedin"));
        fields.add(alias(ats, AtsProfileField.GITHUB_URL, "github"));
        fields.add(alias(ats, AtsProfileField.PORTFOLIO_URL, "portfolio"));

        return new FieldMappingResult(fields);
    }

    private MappedField alias(Optional<CandidateAtsProfile> ats, AtsProfileField field, String alias) {
        return ats.flatMap(p -> atsProfiles.trustedValue(p, field))
                .map(v -> MappedField.mapped(alias, v, "CandidateAtsProfile." + field.fieldName()))
                .orElse(MappedField.unmapped(alias));
    }
}
