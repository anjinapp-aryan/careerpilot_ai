package ai.careerpilot.submission.mapping;

import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.User;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.service.profile.JsonLists;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.16 — maps what is genuinely derivable from {@link User}/{@link CandidateProfile} into
 * the canonical application-form field set, and explicitly marks the rest {@code unmapped}.
 *
 * <p><b>Data-source honesty (audited, see CLAUDE.md):</b> {@code User}/{@code CandidateProfile}/
 * {@code Resume} entities have NO phone/LinkedIn/GitHub/portfolio fields anywhere in the schema —
 * this mapper never fabricates values for them; it reports them {@code unmapped} so the caller
 * (or a human) can fill them in, rather than inventing placeholder text.
 */
@Service
public class FieldMappingService {

    private final UserRepository users;
    private final CandidateProfileRepository profiles;

    public FieldMappingService(UserRepository users, CandidateProfileRepository profiles) {
        this.users = users;
        this.profiles = profiles;
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

        // Never derivable anywhere in the current schema — explicitly unmapped, never fabricated.
        fields.add(MappedField.unmapped("phone"));
        fields.add(MappedField.unmapped("linkedin"));
        fields.add(MappedField.unmapped("github"));
        fields.add(MappedField.unmapped("portfolio"));

        return new FieldMappingResult(fields);
    }
}
