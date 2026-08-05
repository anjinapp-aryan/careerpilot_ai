package ai.careerpilot.execution.browser.form;

import ai.careerpilot.domain.User;
import ai.careerpilot.employerquestion.AnswerConfidence;
import ai.careerpilot.employerquestion.AnswerResolution;
import ai.careerpilot.employerquestion.EmployerAnswerService;
import ai.careerpilot.repo.ApplicationSubmissionAnswerRepository;
import ai.careerpilot.repo.CandidateAtsProfileRepository;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.service.profile.ats.CandidateAtsProfileService;
import ai.careerpilot.submission.mapping.FieldMappingService;
import ai.careerpilot.submission.question.QuestionCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase E — the employer answer library wired into resolution.
 *
 * <p>The invariant under test is the one that makes the whole pipeline safe: only an
 * <em>approved</em> answer may reach the form, and a library that is off, absent, or declining must
 * leave resolution exactly as it was before this phase.
 */
class AnswerResolverEmployerLibraryTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private static final String QUESTION = "Have you previously worked at or consulted for this company?";

    private UserRepository users;
    private CandidateProfileRepository profiles;
    private ApplicationSubmissionAnswerRepository answers;
    private EmployerAnswerService library;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        profiles = mock(CandidateProfileRepository.class);
        answers = mock(ApplicationSubmissionAnswerRepository.class);
        library = mock(EmployerAnswerService.class);

        when(users.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).fullName("Ada Lovelace").email("ada@example.com").build()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(answers.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());
    }

    private AnswerResolver resolverWith(EmployerAnswerService service) {
        FieldMappingService mapping = new FieldMappingService(users, profiles,
                new CandidateAtsProfileService(mock(CandidateAtsProfileRepository.class), false));
        @SuppressWarnings("unchecked")
        ObjectProvider<EmployerAnswerService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return new AnswerResolver(mapping, users, answers, provider);
    }

    @Test
    @DisplayName("an approved library answer is used and carries its provenance")
    void approvedAnswerIsUsed() {
        when(library.isEnabled()).thenReturn(true);
        Instant approvedAt = Instant.now();
        when(library.resolve(eq(userId), anyString(), anyString())).thenReturn(
                new AnswerResolution("No", AnswerConfidence.HUMAN_APPROVED, true, approvedAt,
                        "library", "reused", UUID.randomUUID()));

        AnswerResolver resolver = resolverWith(library);
        ResolvedValue value = resolver.resolve(CanonicalField.SCREENING_QUESTION,
                resolver.loadContext(userId, sessionId), QuestionCategory.OTHER, QUESTION);

        assertThat(value.isResolved()).isTrue();
        assertThat(value.value()).isEqualTo("No");
        assertThat(value.source()).contains("EmployerAnswer").contains("HUMAN_APPROVED");
    }

    @Test
    @DisplayName("an unapproved draft is never used — the library's refusal is honoured")
    void unapprovedDraftIsNotUsed() {
        when(library.isEnabled()).thenReturn(true);
        when(library.resolve(eq(userId), anyString(), anyString())).thenReturn(
                new AnswerResolution(null, AnswerConfidence.AI_SUGGESTED, false, null, "draft",
                        "A draft answer exists but no human has approved it, so it may not be used.",
                        UUID.randomUUID()));

        AnswerResolver resolver = resolverWith(library);
        ResolvedValue value = resolver.resolve(CanonicalField.SCREENING_QUESTION,
                resolver.loadContext(userId, sessionId), QuestionCategory.OTHER, QUESTION);

        assertThat(value.isResolved()).isFalse();
        assertThat(value.value()).isNull();
        assertThat(value.reason()).contains("no human has approved it");
    }

    @Test
    @DisplayName("with the library disabled, resolution is byte-for-byte pre-Phase-E")
    void disabledLibraryChangesNothing() {
        when(library.isEnabled()).thenReturn(false);

        AnswerResolver resolver = resolverWith(library);
        AnswerResolver.ResolutionContext ctx = resolver.loadContext(userId, sessionId);

        // Identity still resolves from the User record exactly as before.
        assertThat(resolver.resolve(CanonicalField.EMAIL, ctx, null, "Email").value())
                .isEqualTo("ada@example.com");
        // A screening question with no stored answer still reports the pre-existing message.
        assertThat(resolver.resolve(CanonicalField.SCREENING_QUESTION, ctx,
                QuestionCategory.OTHER, QUESTION).reason()).contains("no stored answers");
        verify(library, never()).resolve(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("no library bean at all is a supported configuration")
    void absentLibraryIsSupported() {
        FieldMappingService mapping = new FieldMappingService(users, profiles,
                new CandidateAtsProfileService(mock(CandidateAtsProfileRepository.class), false));
        AnswerResolver resolver = new AnswerResolver(mapping, users, answers);

        assertThat(resolver.resolve(CanonicalField.EMAIL,
                resolver.loadContext(userId, sessionId), null, "Email").value())
                .isEqualTo("ada@example.com");
    }

    @Test
    @DisplayName("a library failure fails closed — never 'proceed anyway'")
    void libraryFailureFailsClosed() {
        when(library.isEnabled()).thenReturn(true);
        when(library.resolve(any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        AnswerResolver resolver = resolverWith(library);
        ResolvedValue value = resolver.resolve(CanonicalField.SCREENING_QUESTION,
                resolver.loadContext(userId, sessionId), QuestionCategory.OTHER, QUESTION);

        assertThat(value.isResolved()).isFalse();
        assertThat(value.reason()).contains("employer answer lookup failed");
    }

    @Test
    @DisplayName("an unattributable context never returns a candidate's stored answer")
    void noUserIdMeansNoLibraryLookup() {
        when(library.isEnabled()).thenReturn(true);

        AnswerResolver resolver = resolverWith(library);
        // A context built without a user id — the pre-Phase-E compatibility shape.
        AnswerResolver.ResolutionContext ctx =
                new AnswerResolver.ResolutionContext(java.util.Map.of(), "Ada Lovelace", List.of());

        ResolvedValue value = resolver.resolve(CanonicalField.SCREENING_QUESTION, ctx,
                QuestionCategory.OTHER, QUESTION);

        assertThat(value.isResolved()).isFalse();
        verify(library, never()).resolve(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("the library is queried with the canonical field, so answers cannot cross meanings")
    void lookupIsScopedByCanonicalField() {
        when(library.isEnabled()).thenReturn(true);
        when(library.resolve(any(), anyString(), anyString()))
                .thenReturn(AnswerResolution.none("no match"));

        AnswerResolver resolver = resolverWith(library);
        resolver.resolve(CanonicalField.COUNTRY, resolver.loadContext(userId, sessionId),
                null, "What is your current country of residence?");

        verify(library).resolve(userId, "What is your current country of residence?", "COUNTRY");
    }
}
