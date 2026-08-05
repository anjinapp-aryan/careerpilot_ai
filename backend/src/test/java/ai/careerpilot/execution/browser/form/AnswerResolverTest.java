package ai.careerpilot.execution.browser.form;

import ai.careerpilot.domain.ApplicationSubmissionAnswer;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.User;
import ai.careerpilot.repo.ApplicationSubmissionAnswerRepository;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.submission.mapping.FieldMappingService;
import ai.careerpilot.submission.question.QuestionCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 12C — value resolution. <b>This is where "never fabricate" is enforced</b>, so most of
 * these tests assert that something is <em>not</em> filled.
 */
class AnswerResolverTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private UserRepository users;
    private CandidateProfileRepository profiles;
    private ApplicationSubmissionAnswerRepository answers;
    private AnswerResolver resolver;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        profiles = mock(CandidateProfileRepository.class);
        answers = mock(ApplicationSubmissionAnswerRepository.class);
        resolver = new AnswerResolver(new FieldMappingService(users, profiles, disabledAtsProfiles()), users, answers);

        when(users.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).fullName("Ada Lovelace").email("ada@example.com").build()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(answers.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of());
    }

    private AnswerResolver.ResolutionContext ctx() {
        return resolver.loadContext(userId, sessionId);
    }

    private static ApplicationSubmissionAnswer answer(String question, QuestionCategory category, String text) {
        return ApplicationSubmissionAnswer.builder()
                .id(UUID.randomUUID()).sessionId(UUID.randomUUID())
                .questionText(question).questionCategory(category.name()).answerText(text).build();
    }

    // ── verified values ──

    @Test
    void identityFieldsResolveFromTheUserRecordWithProvenance() {
        AnswerResolver.ResolutionContext ctx = ctx();
        ResolvedValue email = resolver.resolve(CanonicalField.EMAIL, ctx, null, "Email");
        assertThat(email.isResolved()).isTrue();
        assertThat(email.value()).isEqualTo("ada@example.com");
        assertThat(email.source()).isEqualTo("User.email");

        assertThat(resolver.resolve(CanonicalField.FIRST_NAME, ctx, null, "First").value()).isEqualTo("Ada");
        assertThat(resolver.resolve(CanonicalField.LAST_NAME, ctx, null, "Last").value()).isEqualTo("Lovelace");
    }

    @Test
    void aSingleTokenNameNeverInventsASurname() {
        when(users.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).fullName("Prince").email("p@example.com").build()));
        ResolvedValue last = resolver.resolve(CanonicalField.LAST_NAME, ctx(), null, "Last name");
        assertThat(last.isResolved()).isFalse();
        assertThat(last.reason()).contains("no surname token");
    }

    // ── the honesty guarantees ──

    @Test
    /**
     * <b>Deliberately inverted by Phase C.</b> This previously asserted that phone, LinkedIn, GitHub
     * and portfolio could NEVER resolve, because no column for them existed anywhere in the schema.
     * {@code candidate_ats_profile} now backs all four, so the honest expectation changed: they stay
     * unresolved for a user who has not filled them in, but the reason is a profile gap rather than
     * a permanent product gap. The old wording ("no verified source exists") would now be a lie.
     */
    void fieldsWithoutAProfileValueRemainUnresolvedButAreNoLongerPermanentGaps() {
        AnswerResolver.ResolutionContext ctx = ctx();
        for (CanonicalField field : List.of(CanonicalField.PHONE, CanonicalField.LINKEDIN_URL,
                CanonicalField.GITHUB_URL, CanonicalField.PORTFOLIO_URL)) {
            assertThat(field.hasNoDataSource())
                    .as("%s now has a backing column", field).isFalse();

            // This user has set no value, so nothing is invented — and the reason now names the
            // specific field rather than declaring a platform-wide gap.
            ResolvedValue v = resolver.resolve(field, ctx, null, "x");
            assertThat(v.isResolved()).as("%s must not be fabricated", field).isFalse();
            assertThat(v.reason()).contains("no value set for");
        }
    }

    @Test
    void aNoDataSourceFieldIsNeverSatisfiedByALooselyMatchingStoredAnswer() {
        // Guards the check ordering: a stored OTHER-category answer must not leak into a phone field.
        when(answers.findBySessionIdOrderByCreatedAtAsc(sessionId))
                .thenReturn(List.of(answer("Phone number?", QuestionCategory.OTHER, "555-0100")));
        assertThat(resolver.resolve(CanonicalField.PHONE, ctx(), QuestionCategory.OTHER, "Phone number?")
                .isResolved()).isFalse();
    }

    @Test
    void aMissingProfileValueIsUnresolvedNotBlank() {
        ResolvedValue v = resolver.resolve(CanonicalField.YEARS_EXPERIENCE, ctx(), null, "Years");
        assertThat(v.isResolved()).isFalse();
        assertThat(v.value()).isNull();
        assertThat(v.reason()).contains("no value set");
    }

    @Test
    void theGenerationFailurePlaceholderIsNeverTreatedAsAnAnswer() {
        // AnswerGenerationService emits this exact sentinel when the AI call fails. Typing it into
        // a live employer form would be worse than leaving the field blank.
        when(answers.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(
                answer("Why this role?", QuestionCategory.WHY_ROLE,
                        "Unable to generate an answer at this time — please provide this response manually.")));
        ResolvedValue v = resolver.resolve(CanonicalField.SCREENING_QUESTION, ctx(),
                QuestionCategory.WHY_ROLE, "Why this role?");
        assertThat(v.isResolved()).isFalse();
        assertThat(v.reason()).contains("generation-failure placeholder");
    }

    @Test
    void anEmptyStoredAnswerIsUnresolved() {
        when(answers.findBySessionIdOrderByCreatedAtAsc(sessionId))
                .thenReturn(List.of(answer("Why this role?", QuestionCategory.WHY_ROLE, "   ")));
        assertThat(resolver.resolve(CanonicalField.SCREENING_QUESTION, ctx(),
                QuestionCategory.WHY_ROLE, "Why this role?").isResolved()).isFalse();
    }

    @Test
    void withNoStoredAnswersScreeningQuestionsReportTheHonestMessage() {
        ResolvedValue v = resolver.resolve(CanonicalField.SCREENING_QUESTION, ctx(),
                QuestionCategory.WHY_ROLE, "Why this role?");
        assertThat(v.isResolved()).isFalse();
        assertThat(v.reason()).contains("no stored answers");
    }

    @Test
    void anUnknownFieldNeverResolves() {
        assertThat(resolver.resolve(CanonicalField.UNKNOWN, ctx(), null, "???").isResolved()).isFalse();
    }

    // ── matching tiers ──

    @Test
    void exactQuestionTextWinsOverACategorySibling() {
        when(answers.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(
                answer("Tell us why you want this job", QuestionCategory.WHY_ROLE, "CATEGORY SIBLING"),
                answer("Why this role?", QuestionCategory.WHY_ROLE, "EXACT")));
        ResolvedValue v = resolver.resolve(CanonicalField.SCREENING_QUESTION, ctx(),
                QuestionCategory.WHY_ROLE, "Why this role?");
        assertThat(v.value()).isEqualTo("EXACT");
        assertThat(v.source()).contains("exact question match");
    }

    @Test
    void normalisedMatchingToleratesPunctuationAndCase() {
        when(answers.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(
                answer("Why are you interested in this role?", QuestionCategory.WHY_ROLE, "ANSWER")));
        ResolvedValue v = resolver.resolve(CanonicalField.SCREENING_QUESTION, ctx(),
                QuestionCategory.WHY_ROLE, "  why are you interested in this role  ");
        assertThat(v.value()).isEqualTo("ANSWER");
        assertThat(v.source()).contains("normalised");
    }

    /**
     * The property that makes the engine portable: two ATSes word the same question differently,
     * and both find the same stored answer through the shared category.
     */
    @Test
    void categoryMatchingBridgesDifferentAtsWordings() {
        when(answers.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(
                answer("What are your salary expectations?", QuestionCategory.SALARY, "Market rate for the role")));
        ResolvedValue v = resolver.resolve(CanonicalField.SALARY_EXPECTATION, ctx(),
                QuestionCategory.SALARY, "Desired compensation");
        assertThat(v.value()).isEqualTo("Market rate for the role");
        assertThat(v.source()).contains("category match");
    }

    @Test
    void theOtherCategoryNeverMatchesByCategoryAlone() {
        // OTHER is the classifier's "I don't know" — matching on it would pair arbitrary questions.
        when(answers.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(
                answer("Some unrelated question", QuestionCategory.OTHER, "UNRELATED")));
        assertThat(resolver.resolve(CanonicalField.SCREENING_QUESTION, ctx(),
                QuestionCategory.OTHER, "A totally different question").isResolved()).isFalse();
    }

    @Test
    void aStructuredProfileValueBeatsAProseAnswerForSalary() {
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(
                CandidateProfile.builder().userId(userId)
                        .salaryTarget(new java.math.BigDecimal("120000")).build()));
        when(answers.findBySessionIdOrderByCreatedAtAsc(sessionId)).thenReturn(List.of(
                answer("Salary?", QuestionCategory.SALARY, "I'd expect something competitive")));
        ResolvedValue v = resolver.resolve(CanonicalField.SALARY_EXPECTATION, ctx(),
                QuestionCategory.SALARY, "Salary?");
        assertThat(v.value()).contains("120000");
        assertThat(v.source()).contains("CandidateProfile");
    }

    // ── resilience ──

    @Test
    void aFailingRepositoryDegradesToUnresolvedRatherThanThrowing() {
        when(users.findById(userId)).thenThrow(new IllegalStateException("db down"));
        AnswerResolver.ResolutionContext ctx = resolver.loadContext(userId, sessionId);
        assertThat(resolver.resolve(CanonicalField.EMAIL, ctx, null, "Email").isResolved()).isFalse();
    }

    @Test
    void aNullSessionMeansNoAnswersRatherThanAnError() {
        AnswerResolver.ResolutionContext ctx = resolver.loadContext(userId, null);
        assertThat(ctx.storedAnswers()).isEmpty();
        assertThat(resolver.resolve(CanonicalField.SCREENING_QUESTION, ctx,
                QuestionCategory.WHY_ROLE, "Why?").isResolved()).isFalse();
    }

    @Test
    void fileFieldsAreExplicitlyDelegatedToThePackageNotResolvedHere() {
        AnswerResolver.ResolutionContext ctx = ctx();
        assertThat(resolver.resolve(CanonicalField.RESUME_UPLOAD, ctx, null, "Resume").reason())
                .contains("ApplicationPackage");
        assertThat(resolver.resolve(CanonicalField.COVER_LETTER_TEXT, ctx, null, "Cover letter").reason())
                .contains("ApplicationPackage");
    }

    /**
     * Phase C added an ATS-profile source to the mapper. These pre-Phase-C tests deliberately pass a
     * DISABLED one: with the flag off the mapper must behave exactly as it did before the phase, so
     * every assertion below doubles as a backward-compatibility check.
     */
    private static ai.careerpilot.service.profile.ats.CandidateAtsProfileService disabledAtsProfiles() {
        return new ai.careerpilot.service.profile.ats.CandidateAtsProfileService(org.mockito.Mockito.mock(ai.careerpilot.repo.CandidateAtsProfileRepository.class), false);
    }
}
