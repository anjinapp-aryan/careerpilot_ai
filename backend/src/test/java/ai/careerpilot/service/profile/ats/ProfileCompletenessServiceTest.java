package ai.careerpilot.service.profile.ats;

import ai.careerpilot.domain.CandidateAtsProfile;
import ai.careerpilot.domain.FieldVerificationSource;
import ai.careerpilot.repo.CandidateAtsProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Phase C — completeness must be evidence-based: every figure traceable to real fields. */
class ProfileCompletenessServiceTest {

    private final CandidateAtsProfileRepository repository = mock(CandidateAtsProfileRepository.class);
    private final UUID userId = UUID.randomUUID();

    private CandidateAtsProfileService profiles;
    private ProfileCompletenessService completeness;

    @BeforeEach
    void setUp() {
        profiles = new CandidateAtsProfileService(repository, true);
        completeness = new ProfileCompletenessService(profiles);
        when(repository.save(any(CandidateAtsProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
    }

    private CandidateAtsProfile withAllRequired(FieldVerificationSource source) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (AtsProfileField f : AtsProfileField.withImportance(AtsProfileField.Importance.REQUIRED)) {
            values.put(f.fieldName(), "value-for-" + f.fieldName());
        }
        return profiles.update(userId, values, source).orElseThrow();
    }

    @Test
    @DisplayName("an empty profile scores zero and lists every field as missing")
    void emptyProfileIsHonest() {
        ProfileCompleteness result = completeness.evaluate(userId);

        assertThat(result.overallPercent()).isZero();
        assertThat(result.browserReadiness()).isZero();
        assertThat(result.browserAutomationReady()).isFalse();
        assertThat(result.missingRequired())
                .containsExactlyInAnyOrderElementsOf(
                        AtsProfileField.withImportance(AtsProfileField.Importance.REQUIRED).stream()
                                .map(AtsProfileField::fieldName).toList());
    }

    @Test
    @DisplayName("with the feature off the result is reported as disabled, not as a complete profile")
    void disabledIsNotComplete() {
        ProfileCompletenessService off = new ProfileCompletenessService(
                new CandidateAtsProfileService(repository, false));

        ProfileCompleteness result = off.evaluate(userId);

        assertThat(result.enabled()).isFalse();
        assertThat(result.overallPercent()).isZero();
        assertThat(result.browserAutomationReady()).isFalse();
    }

    @Test
    @DisplayName("all required fields verified means browser-ready, even with optionals missing")
    void requiredVerifiedIsBrowserReady() {
        CandidateAtsProfile profile = withAllRequired(FieldVerificationSource.USER_ENTERED);

        ProfileCompleteness result = completeness.evaluate(profile);

        assertThat(result.missingRequired()).isEmpty();
        assertThat(result.browserReadiness()).isEqualTo(100);
        assertThat(result.browserAutomationReady()).isTrue();
        assertThat(result.missingOptional()).isNotEmpty();
        assertThat(result.overallPercent()).isLessThan(100);
    }

    @Test
    @DisplayName("required fields present but AI-suggested are NOT browser-ready")
    void unverifiedRequiredBlocksBrowserReadiness() {
        CandidateAtsProfile profile = withAllRequired(FieldVerificationSource.AI_SUGGESTED);

        ProfileCompleteness result = completeness.evaluate(profile);

        // They are present, so they are not "missing"...
        assertThat(result.missingRequired()).isEmpty();
        assertThat(result.atsReadiness()).isGreaterThan(0);
        // ...but automation may not use them, and the score says so rather than promising a
        // capability that would then be refused.
        assertThat(result.browserReadiness()).isZero();
        assertThat(result.browserAutomationReady()).isFalse();
        assertThat(result.unverifiedFields()).containsKey("phone");
        assertThat(result.unverifiedFields().get("phone")).contains("AI_SUGGESTED");
    }

    @Test
    @DisplayName("optional fields cannot inflate the score past what required fields allow")
    void weightingFavoursRequiredFields() {
        Map<String, Object> onlyOptional = new LinkedHashMap<>();
        for (AtsProfileField f : AtsProfileField.withImportance(AtsProfileField.Importance.OPTIONAL)) {
            onlyOptional.put(f.fieldName(), "x");
        }
        CandidateAtsProfile optionalOnly =
                profiles.update(userId, onlyOptional, FieldVerificationSource.USER_ENTERED).orElseThrow();

        ProfileCompleteness result = completeness.evaluate(optionalOnly);

        // Eleven optional fields filled and still under half: a user cannot reach a comfortable
        // number without touching the fields that actually block a submission.
        assertThat(result.overallPercent()).isLessThan(50);
        assertThat(result.browserReadiness()).isZero();
    }

    @Test
    @DisplayName("resume readiness is computed from resume-relevant fields, not the whole catalogue")
    void resumeReadinessIsItsOwnQuestion() {
        CandidateAtsProfile profile = profiles.update(userId, Map.of(
                        "currentCompany", "GitLab",
                        "currentTitle", "Staff Engineer",
                        "university", "IISc",
                        "highestEducation", "Masters"),
                FieldVerificationSource.USER_ENTERED).orElseThrow();

        ProfileCompleteness result = completeness.evaluate(profile);

        assertThat(result.resumeReadiness()).isGreaterThan(0);
        // A postal code is irrelevant to a resume, so it must not depress this score.
        assertThat(result.missingOptional()).contains("postalCode");
    }

    @Test
    @DisplayName("every tracked field appears in exactly one missing bucket when absent")
    void bucketsPartitionTheCatalogue() {
        ProfileCompleteness result = completeness.evaluate(CandidateAtsProfile.builder()
                .userId(userId).build());

        int totalMissing = result.missingRequired().size()
                + result.missingRecommended().size()
                + result.missingOptional().size();

        assertThat(totalMissing).isEqualTo(AtsProfileField.values().length);
    }
}
