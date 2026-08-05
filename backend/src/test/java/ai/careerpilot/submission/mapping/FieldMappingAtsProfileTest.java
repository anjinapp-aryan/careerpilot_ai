package ai.careerpilot.submission.mapping;

import ai.careerpilot.domain.CandidateAtsProfile;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.FieldVerificationSource;
import ai.careerpilot.domain.User;
import ai.careerpilot.execution.browser.form.CanonicalField;
import ai.careerpilot.repo.CandidateAtsProfileRepository;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.service.profile.ats.AtsProfileField;
import ai.careerpilot.service.profile.ats.CandidateAtsProfileService;
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

/**
 * Phase C — the acceptance contract: every ATS canonical field must resolve from verified profile
 * data, and nothing must change when the feature is off.
 */
class FieldMappingAtsProfileTest {

    private final UserRepository users = mock(UserRepository.class);
    private final CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
    private final CandidateAtsProfileRepository atsRepo = mock(CandidateAtsProfileRepository.class);
    private final UUID userId = UUID.randomUUID();

    private CandidateAtsProfileService atsService;

    @BeforeEach
    void setUp() {
        atsService = new CandidateAtsProfileService(atsRepo, true);
        when(atsRepo.save(any(CandidateAtsProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        when(atsRepo.findByUserId(userId)).thenReturn(Optional.empty());
        when(users.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).fullName("Anjinappa N").email("a@example.com").build()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(
                CandidateProfile.builder().userId(userId).homeCountry("India").build()));
    }

    private CandidateAtsProfile seed(FieldVerificationSource source) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (AtsProfileField f : AtsProfileField.values()) {
            values.put(f.fieldName(), switch (f) {
                case CURRENT_SALARY -> "1200000";
                case GRADUATION_YEAR -> "2012";
                default -> "value-" + f.fieldName();
            });
        }
        return atsService.update(userId, values, source).orElseThrow();
    }

    private FieldMappingResult mapWith(CandidateAtsProfileService service) {
        return new FieldMappingService(users, profiles, service).map(userId);
    }

    @Test
    @DisplayName("with the flag off every Phase C field is unmapped — pre-Phase-C behaviour")
    void flagOffIsUnchanged() {
        FieldMappingResult result = mapWith(
                new CandidateAtsProfileService(atsRepo, false));

        Map<String, MappedField> byName = index(result);
        assertThat(byName.get("phone").unmapped()).isTrue();
        assertThat(byName.get("linkedin").unmapped()).isTrue();
        assertThat(byName.get("github").unmapped()).isTrue();
        assertThat(byName.get("portfolio").unmapped()).isTrue();
        // The pre-existing fields still resolve exactly as before.
        assertThat(byName.get("fullName").value()).isEqualTo("Anjinappa N");
        assertThat(byName.get("email").value()).isEqualTo("a@example.com");
    }

    @Test
    @DisplayName("every ATS profile field resolves once verified data exists")
    void allFieldsResolve() {
        CandidateAtsProfile seeded = seed(FieldVerificationSource.USER_ENTERED);
        when(atsRepo.findByUserId(userId)).thenReturn(Optional.of(seeded));

        Map<String, MappedField> byName = index(mapWith(atsService));

        for (AtsProfileField f : AtsProfileField.values()) {
            assertThat(byName).containsKey(f.fieldName());
            assertThat(byName.get(f.fieldName()).unmapped())
                    .withFailMessage("expected %s to resolve", f.fieldName()).isFalse();
            assertThat(byName.get(f.fieldName()).source())
                    .isEqualTo("CandidateAtsProfile." + f.fieldName());
        }
    }

    @Test
    @DisplayName("AI-suggested values map as unmapped — an unconfirmed guess never reaches a form")
    void aiSuggestedIsNotMapped() {
        CandidateAtsProfile seeded = seed(FieldVerificationSource.AI_SUGGESTED);
        when(atsRepo.findByUserId(userId)).thenReturn(Optional.of(seeded));

        Map<String, MappedField> byName = index(mapWith(atsService));

        assertThat(byName.get("phone").unmapped()).isTrue();
        assertThat(byName.get("phone").value()).isNull();
        assertThat(byName.get("workAuthorization").unmapped()).isTrue();
    }

    @Test
    @DisplayName("legacy aliases keep resolving, now with real values behind them")
    void legacyAliasesStillWork() {
        CandidateAtsProfile seeded = seed(FieldVerificationSource.USER_ENTERED);
        when(atsRepo.findByUserId(userId)).thenReturn(Optional.of(seeded));

        Map<String, MappedField> byName = index(mapWith(atsService));

        assertThat(byName.get("linkedin").unmapped()).isFalse();
        assertThat(byName.get("github").unmapped()).isFalse();
        assertThat(byName.get("portfolio").unmapped()).isFalse();
    }

    @Test
    @DisplayName("no canonical ATS field is left without a data source")
    void canonicalFieldsAllHaveASource() {
        // The Phase C acceptance criterion, asserted directly: hasNoDataSource() must be empty,
        // because every field that had no backing column now has one.
        for (CanonicalField field : CanonicalField.values()) {
            assertThat(field.hasNoDataSource())
                    .withFailMessage("%s still reports no data source", field).isFalse();
        }
    }

    private static Map<String, MappedField> index(FieldMappingResult result) {
        Map<String, MappedField> out = new LinkedHashMap<>();
        for (MappedField f : result.fields()) out.putIfAbsent(f.fieldName(), f);
        return out;
    }
}
