package ai.careerpilot.service.profile.ats;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase C — how complete a candidate profile actually is, and what specifically is missing.
 *
 * <p><b>Every number here is a count of real fields, never an estimate.</b> The three readiness
 * scores answer three genuinely different questions and are deliberately not averaged into one
 * headline figure: a profile can be perfectly ready for résumé generation while missing the phone
 * number that stops every browser submission, and a single blended percentage would hide exactly
 * that. Each score names the fields it is computed from.
 *
 * @param overallPercent     weighted completion across all tracked fields
 * @param atsReadiness       share of REQUIRED + RECOMMENDED fields present — what a form needs
 * @param resumeReadiness    share of the résumé-relevant fields present
 * @param browserReadiness   share of REQUIRED fields present <b>and verified</b>; the only score
 *                           that additionally demands trusted provenance, because it is the only
 *                           one whose output is typed into a real employer's form
 * @param missingRequired    field names of absent REQUIRED fields
 * @param missingRecommended field names of absent RECOMMENDED fields
 * @param missingOptional    field names of absent OPTIONAL fields
 * @param unverifiedFields   fields that hold a value automation may not use, with the reason
 */
public record ProfileCompleteness(
        int overallPercent,
        int atsReadiness,
        int resumeReadiness,
        int browserReadiness,
        List<String> missingRequired,
        List<String> missingRecommended,
        List<String> missingOptional,
        Map<String, String> unverifiedFields,
        boolean enabled) {

    public ProfileCompleteness {
        missingRequired = missingRequired == null ? List.of() : List.copyOf(missingRequired);
        missingRecommended = missingRecommended == null ? List.of() : List.copyOf(missingRecommended);
        missingOptional = missingOptional == null ? List.of() : List.copyOf(missingOptional);
        unverifiedFields = unverifiedFields == null ? Map.of() : Map.copyOf(unverifiedFields);
    }

    /**
     * The state when the feature is off or no profile row exists. <b>Zero, not null and not an
     * optimistic default</b> — an unmeasured profile is not a complete one, and every tracked field
     * is correctly reported as missing so a guided-completion UI has something to render.
     */
    public static ProfileCompleteness empty(boolean enabled) {
        return new ProfileCompleteness(0, 0, 0, 0,
                names(AtsProfileField.Importance.REQUIRED),
                names(AtsProfileField.Importance.RECOMMENDED),
                names(AtsProfileField.Importance.OPTIONAL),
                Map.of(), enabled);
    }

    private static List<String> names(AtsProfileField.Importance importance) {
        return AtsProfileField.withImportance(importance).stream()
                .map(AtsProfileField::fieldName).toList();
    }

    /** True when nothing blocks browser automation from filling the fields this table owns. */
    public boolean browserAutomationReady() {
        return enabled && missingRequired.isEmpty() && browserReadiness == 100;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("overallPercent", overallPercent);
        out.put("atsReadiness", atsReadiness);
        out.put("resumeReadiness", resumeReadiness);
        out.put("browserReadiness", browserReadiness);
        out.put("browserAutomationReady", browserAutomationReady());
        out.put("missingRequired", missingRequired);
        out.put("missingRecommended", missingRecommended);
        out.put("missingOptional", missingOptional);
        out.put("unverifiedFields", unverifiedFields);
        return out;
    }
}
