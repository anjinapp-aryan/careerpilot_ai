package ai.careerpilot.service.profile.ats;

import ai.careerpilot.domain.CandidateAtsProfile;
import ai.careerpilot.domain.FieldVerificationSource;
import ai.careerpilot.repo.CandidateAtsProfileRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Phase C — provenance recording and the trust boundary automation depends on. */
class CandidateAtsProfileServiceTest {

    private final CandidateAtsProfileRepository repository = mock(CandidateAtsProfileRepository.class);
    private final UUID userId = UUID.randomUUID();

    private CandidateAtsProfileService enabled;
    private CandidateAtsProfileService disabled;

    @BeforeEach
    void setUp() {
        enabled = new CandidateAtsProfileService(repository, true);
        disabled = new CandidateAtsProfileService(repository, false);
        when(repository.save(any(CandidateAtsProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) m.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return m;
    }

    @Nested
    @DisplayName("feature flag")
    class Flag {

        @Test
        @DisplayName("with the flag off nothing is read or written")
        void darkByDefault() {
            when(repository.findByUserId(userId)).thenReturn(Optional.empty());

            assertThat(disabled.get(userId)).isEmpty();
            assertThat(disabled.update(userId, values("phone", "+1 555 0100"),
                    FieldVerificationSource.USER_ENTERED)).isEmpty();

            verifyNoInteractions(repository);
        }
    }

    @Nested
    @DisplayName("provenance")
    class Provenance {

        @Test
        @DisplayName("a written field records the source it was written with")
        void recordsSource() {
            when(repository.findByUserId(userId)).thenReturn(Optional.empty());

            CandidateAtsProfile saved = enabled.update(userId,
                    values("phone", "+1 555 0100"), FieldVerificationSource.USER_ENTERED).orElseThrow();

            assertThat(saved.getPhone()).isEqualTo("+1 555 0100");
            assertThat(enabled.sourceOf(saved, "phone")).isEqualTo(FieldVerificationSource.USER_ENTERED);
            assertThat(enabled.trustedValue(saved, AtsProfileField.PHONE)).contains("+1 555 0100");
        }

        @Test
        @DisplayName("an AI suggestion is stored but invisible to automation")
        void aiSuggestionIsNotTrusted() {
            when(repository.findByUserId(userId)).thenReturn(Optional.empty());

            CandidateAtsProfile saved = enabled.update(userId,
                    values("currentTitle", "Principal Engineer"),
                    FieldVerificationSource.AI_SUGGESTED).orElseThrow();

            // Stored, so a human can review it...
            assertThat(saved.getCurrentTitle()).isEqualTo("Principal Engineer");
            assertThat(AtsProfileField.CURRENT_TITLE.read(saved)).contains("Principal Engineer");
            // ...but automation may not use it.
            assertThat(enabled.trustedValue(saved, AtsProfileField.CURRENT_TITLE)).isEmpty();
        }

        @Test
        @DisplayName("confirming a suggestion makes it usable")
        void humanConfirmationPromotes() {
            when(repository.findByUserId(userId)).thenReturn(Optional.empty());
            CandidateAtsProfile first = enabled.update(userId,
                    values("currentTitle", "Principal Engineer"),
                    FieldVerificationSource.AI_SUGGESTED).orElseThrow();

            when(repository.findByUserId(userId)).thenReturn(Optional.of(first));
            CandidateAtsProfile confirmed = enabled.update(userId,
                    values("currentTitle", "Principal Engineer"),
                    FieldVerificationSource.HUMAN_CONFIRMED).orElseThrow();

            assertThat(enabled.trustedValue(confirmed, AtsProfileField.CURRENT_TITLE))
                    .contains("Principal Engineer");
        }

        @Test
        @DisplayName("a value with no recorded provenance is untrusted, never assumed verified")
        void missingProvenanceFailsClosed() {
            CandidateAtsProfile orphan = CandidateAtsProfile.builder()
                    .userId(userId).phone("+1 555 0100").build();

            assertThat(orphan.getPhone()).isNotNull();
            assertThat(enabled.sourceOf(orphan, "phone")).isEqualTo(FieldVerificationSource.AI_SUGGESTED);
            assertThat(enabled.trustedValue(orphan, AtsProfileField.PHONE)).isEmpty();
        }

        @Test
        @DisplayName("unreadable provenance json degrades to untrusted rather than throwing")
        void corruptProvenanceIsSafe() {
            CandidateAtsProfile corrupt = CandidateAtsProfile.builder()
                    .userId(userId).phone("+1 555 0100").fieldSourcesJson("{not json").build();

            assertThat(enabled.trustedValue(corrupt, AtsProfileField.PHONE)).isEmpty();
        }
    }

    @Nested
    @DisplayName("patch semantics")
    class Patching {

        @Test
        @DisplayName("absent keys leave existing values alone")
        void partialUpdateDoesNotClear() {
            CandidateAtsProfile existing = CandidateAtsProfile.builder()
                    .userId(userId).phone("+1 555 0100").city("Bengaluru").build();
            when(repository.findByUserId(userId)).thenReturn(Optional.of(existing));

            CandidateAtsProfile saved = enabled.update(userId, values("city", "Berlin"),
                    FieldVerificationSource.USER_ENTERED).orElseThrow();

            assertThat(saved.getCity()).isEqualTo("Berlin");
            assertThat(saved.getPhone()).isEqualTo("+1 555 0100");
        }

        @Test
        @DisplayName("a null or blank value is ignored, not written as empty")
        void blankIsIgnored() {
            CandidateAtsProfile existing = CandidateAtsProfile.builder()
                    .userId(userId).phone("+1 555 0100").build();
            when(repository.findByUserId(userId)).thenReturn(Optional.of(existing));

            Map<String, Object> patch = values("city", "   ");
            patch.put("phone", null);
            CandidateAtsProfile saved = enabled.update(userId, patch,
                    FieldVerificationSource.USER_ENTERED).orElseThrow();

            assertThat(saved.getPhone()).isEqualTo("+1 555 0100");
            assertThat(saved.getCity()).isNull();
        }

        @Test
        @DisplayName("an unknown field name is ignored, never persisted")
        void unknownFieldIgnored() {
            when(repository.findByUserId(userId)).thenReturn(Optional.empty());

            CandidateAtsProfile saved = enabled.update(userId,
                    values("favouriteColour", "blue"), FieldVerificationSource.USER_ENTERED).orElseThrow();

            assertThat(enabled.sourceOf(saved, "favouriteColour"))
                    .isEqualTo(FieldVerificationSource.AI_SUGGESTED);
        }

        @Test
        @DisplayName("a non-numeric salary is rejected rather than coerced")
        void badSalaryRejected() {
            when(repository.findByUserId(userId)).thenReturn(Optional.empty());

            CandidateAtsProfile saved = enabled.update(userId,
                    values("currentSalary", "lots"), FieldVerificationSource.USER_ENTERED).orElseThrow();

            assertThat(saved.getCurrentSalary()).isNull();
        }

        @Test
        @DisplayName("a formatted salary is parsed; an impossible graduation year is rejected")
        void numericParsing() {
            when(repository.findByUserId(userId)).thenReturn(Optional.empty());

            CandidateAtsProfile saved = enabled.update(userId,
                    values("currentSalary", "5,000,000", "graduationYear", "3500"),
                    FieldVerificationSource.USER_ENTERED).orElseThrow();

            assertThat(saved.getCurrentSalary()).isEqualByComparingTo(new BigDecimal("5000000"));
            assertThat(saved.getGraduationYear()).isNull();
        }

        @Test
        @DisplayName("comma-separated lists become json arrays")
        void listsAreParsed() {
            when(repository.findByUserId(userId)).thenReturn(Optional.empty());

            CandidateAtsProfile saved = enabled.update(userId,
                    values("languages", "English, German, Kannada"),
                    FieldVerificationSource.USER_ENTERED).orElseThrow();

            assertThat(AtsProfileField.LANGUAGES.read(saved)).contains("English, German, Kannada");
        }
    }

    @Test
    @DisplayName("a read failure degrades to empty rather than propagating")
    void readFailureIsIsolated() {
        when(repository.findByUserId(userId)).thenThrow(new RuntimeException("db down"));

        assertThat(enabled.get(userId)).isEmpty();
        verify(repository, never()).save(any());
    }
}
