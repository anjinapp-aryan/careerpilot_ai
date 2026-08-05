package ai.careerpilot.api;

import ai.careerpilot.domain.CandidateAtsProfile;
import ai.careerpilot.domain.FieldVerificationSource;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.profile.ats.AtsProfileField;
import ai.careerpilot.service.profile.ats.CandidateAtsProfileService;
import ai.careerpilot.service.profile.ats.ProfileCompleteness;
import ai.careerpilot.service.profile.ats.ProfileCompletenessService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase C — backend support for a guided profile-completion workflow. Backend only; no frontend is
 * built in this phase.
 *
 * <p>Three endpoints, deliberately not one per field: a completion UI needs the whole picture in a
 * single read, or its progress bar and its field list can disagree about the same profile.
 *
 * <p>Multi-tenant isolation follows this codebase's manual convention — every method scopes to
 * {@code user.userId()} and no endpoint accepts a user id from the caller.
 *
 * <p>All three return {@code 200} with an explicit {@code enabled:false} payload rather than
 * {@code 404} when the feature is off, matching the convention {@code GET /api/career-timeline}
 * established, so a client can distinguish "disabled" from "nothing filled in yet".
 */
@RestController
@RequestMapping("/api/candidate-ats-profile")
public class CandidateAtsProfileController {

    private final CandidateAtsProfileService profiles;
    private final ProfileCompletenessService completeness;

    public CandidateAtsProfileController(CandidateAtsProfileService profiles,
                                         ProfileCompletenessService completeness) {
        this.profiles = profiles;
        this.completeness = completeness;
    }

    /** The stored profile plus per-field provenance. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> get(AuthenticatedUser user) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", profiles.isEnabled());
        Optional<CandidateAtsProfile> profile = profiles.get(user.userId());
        body.put("present", profile.isPresent());
        body.put("fields", profile.map(this::fieldView).orElse(Map.of()));
        return ResponseEntity.ok(body);
    }

    /**
     * Apply a partial update. Absent keys are left alone, so a UI may submit one section at a time
     * without clearing the others.
     *
     * <p>{@code source} defaults to {@code USER_ENTERED}. A caller may declare a different
     * provenance (an import, an AI suggestion), and an {@code AI_SUGGESTED} value is stored but
     * remains invisible to automation until it is re-submitted as {@code HUMAN_CONFIRMED}.
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> update(AuthenticatedUser user,
                                                      @RequestBody Map<String, Object> body) {
        if (!profiles.isEnabled()) {
            return ResponseEntity.ok(Map.of("enabled", false, "updated", false));
        }
        Object declared = body == null ? null : body.get("source");
        FieldVerificationSource source = declared == null
                ? FieldVerificationSource.USER_ENTERED
                : FieldVerificationSource.parseOrUntrusted(String.valueOf(declared));

        Map<String, Object> values = new LinkedHashMap<>(body == null ? Map.of() : body);
        values.remove("source");

        Optional<CandidateAtsProfile> saved = profiles.update(user.userId(), values, source);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("updated", saved.isPresent());
        out.put("fields", saved.map(this::fieldView).orElse(Map.of()));
        out.put("completeness", completeness.evaluate(user.userId()).snapshot());
        return ResponseEntity.ok(out);
    }

    /**
     * What is missing and how ready the profile is. This is the endpoint a guided-completion UI
     * drives from: it carries the progress figures and the specific missing-field lists in one
     * payload, so a "72% complete" bar and the list beneath it are always computed from one read.
     */
    @GetMapping("/completeness")
    public ResponseEntity<Map<String, Object>> completeness(AuthenticatedUser user) {
        ProfileCompleteness result = completeness.evaluate(user.userId());
        Map<String, Object> out = new LinkedHashMap<>(result.snapshot());
        out.put("catalogue", catalogue());
        return ResponseEntity.ok(out);
    }

    /** The field catalogue, so a UI can render sections without hardcoding the field list. */
    private List<Map<String, Object>> catalogue() {
        return java.util.Arrays.stream(AtsProfileField.values())
                .map(f -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("field", f.fieldName());
                    m.put("importance", f.importance().name());
                    return m;
                })
                .toList();
    }

    /**
     * Values with their provenance. Never a bare value map — a client that cannot see where a value
     * came from cannot show the user which fields still need confirming, which is the whole point
     * of the guided workflow.
     */
    private Map<String, Object> fieldView(CandidateAtsProfile profile) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (AtsProfileField field : AtsProfileField.values()) {
            Optional<String> value = field.read(profile);
            if (value.isEmpty()) continue;
            FieldVerificationSource source = profiles.sourceOf(profile, field.fieldName());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("value", value.get());
            entry.put("source", source.name());
            entry.put("trustedForAutomation", source.isTrustedForAutomation());
            out.put(field.fieldName(), entry);
        }
        return out;
    }
}
