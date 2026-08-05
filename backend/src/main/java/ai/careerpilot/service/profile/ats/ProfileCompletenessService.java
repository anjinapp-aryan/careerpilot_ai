package ai.careerpilot.service.profile.ats;

import ai.careerpilot.domain.CandidateAtsProfile;
import ai.careerpilot.domain.FieldVerificationSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase C — evidence-based profile completeness.
 *
 * <p>Pure and deterministic apart from the single profile read: no LLM, no estimation, no smoothing.
 * Every percentage is {@code present / tracked} over a field set enumerated in
 * {@link AtsProfileField}, so a number can always be traced back to the exact fields that produced
 * it — the same discipline as {@code AutomationConfidence} and {@code RecommendedActionEngine}.
 *
 * <p><b>The weighting is the honest part.</b> A REQUIRED field counts three times a RECOMMENDED one
 * and nine times an OPTIONAL one, because a missing phone number blocks a submission outright while
 * a missing postal code rarely does. Weighting everything equally would let a user fill twelve
 * optional fields, watch the number climb past 70%, and still be unable to submit a single
 * application — a score that moves without the underlying capability moving is worse than no score.
 */
@Service
public class ProfileCompletenessService {

    private static final int WEIGHT_REQUIRED = 9;
    private static final int WEIGHT_RECOMMENDED = 3;
    private static final int WEIGHT_OPTIONAL = 1;

    /**
     * The fields a résumé-generation surface actually draws on. Deliberately a subset: a résumé does
     * not carry a postal code or a security clearance, so counting those against résumé readiness
     * would report a complete résumé profile as incomplete.
     */
    private static final List<AtsProfileField> RESUME_FIELDS = List.of(
            AtsProfileField.CURRENT_COMPANY,
            AtsProfileField.CURRENT_TITLE,
            AtsProfileField.HIGHEST_EDUCATION,
            AtsProfileField.UNIVERSITY,
            AtsProfileField.DEGREE,
            AtsProfileField.FIELD_OF_STUDY,
            AtsProfileField.GRADUATION_YEAR,
            AtsProfileField.LINKEDIN_URL,
            AtsProfileField.GITHUB_URL,
            AtsProfileField.CITY,
            AtsProfileField.COUNTRY,
            AtsProfileField.CERTIFICATIONS,
            AtsProfileField.LANGUAGES);

    private final CandidateAtsProfileService profiles;

    public ProfileCompletenessService(CandidateAtsProfileService profiles) {
        this.profiles = profiles;
    }

    /** Never throws. An absent profile reports zero with every field listed as missing. */
    public ProfileCompleteness evaluate(UUID userId) {
        if (!profiles.isEnabled()) return ProfileCompleteness.empty(false);
        Optional<CandidateAtsProfile> found = profiles.get(userId);
        if (found.isEmpty()) return ProfileCompleteness.empty(true);
        return evaluate(found.get());
    }

    /** The pure half — directly testable without a repository. */
    public ProfileCompleteness evaluate(CandidateAtsProfile profile) {
        List<String> missingRequired = new ArrayList<>();
        List<String> missingRecommended = new ArrayList<>();
        List<String> missingOptional = new ArrayList<>();
        Map<String, String> unverified = new LinkedHashMap<>();

        int weightTotal = 0;
        int weightPresent = 0;
        int atsTracked = 0;
        int atsPresent = 0;
        int requiredTracked = 0;
        int requiredVerified = 0;

        for (AtsProfileField field : AtsProfileField.values()) {
            int weight = weightOf(field);
            boolean present = field.read(profile).isPresent();
            weightTotal += weight;
            if (present) weightPresent += weight;

            if (present) {
                FieldVerificationSource source = profiles.sourceOf(profile, field.fieldName());
                if (!source.isTrustedForAutomation()) {
                    unverified.put(field.fieldName(),
                            "value present but source is " + source + " — needs human confirmation "
                                    + "before automation may use it");
                }
            } else {
                switch (field.importance()) {
                    case REQUIRED -> missingRequired.add(field.fieldName());
                    case RECOMMENDED -> missingRecommended.add(field.fieldName());
                    case OPTIONAL -> missingOptional.add(field.fieldName());
                }
            }

            if (field.importance() != AtsProfileField.Importance.OPTIONAL) {
                atsTracked++;
                if (present) atsPresent++;
            }
            if (field.importance() == AtsProfileField.Importance.REQUIRED) {
                requiredTracked++;
                // Browser readiness demands BOTH presence and trusted provenance. A required field
                // holding an unreviewed AI suggestion is not something we may submit, so counting
                // it here would promise a capability automation would then refuse to exercise.
                if (profiles.trustedValue(profile, field).isPresent()) requiredVerified++;
            }
        }

        return new ProfileCompleteness(
                percent(weightPresent, weightTotal),
                percent(atsPresent, atsTracked),
                resumeReadiness(profile),
                percent(requiredVerified, requiredTracked),
                missingRequired, missingRecommended, missingOptional, unverified, true);
    }

    private int resumeReadiness(CandidateAtsProfile profile) {
        int present = 0;
        for (AtsProfileField field : RESUME_FIELDS) {
            if (field.read(profile).isPresent()) present++;
        }
        return percent(present, RESUME_FIELDS.size());
    }

    private static int weightOf(AtsProfileField field) {
        return switch (field.importance()) {
            case REQUIRED -> WEIGHT_REQUIRED;
            case RECOMMENDED -> WEIGHT_RECOMMENDED;
            case OPTIONAL -> WEIGHT_OPTIONAL;
        };
    }

    /**
     * A zero denominator reports 100 rather than 0: "there is nothing to complete" and "nothing has
     * been completed" are different findings, and the field catalogue is never empty in practice.
     */
    private static int percent(int present, int total) {
        if (total <= 0) return 100;
        return (int) Math.round(100.0 * present / total);
    }
}
