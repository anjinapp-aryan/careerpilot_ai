package ai.careerpilot.execution.browser.form;

import ai.careerpilot.domain.User;
import ai.careerpilot.repo.ApplicationSubmissionAnswerRepository;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.submission.mapping.FieldMappingService;
import ai.careerpilot.submission.question.QuestionDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Phase 12C — planning: the complete fill decision, made before any keystroke is sent. */
class FormFillPlannerTest {

    private final UUID userId = UUID.randomUUID();
    private FormFillPlanner planner;
    private UserRepository users;
    private CandidateProfileRepository profiles;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        profiles = mock(CandidateProfileRepository.class);
        ApplicationSubmissionAnswerRepository answers = mock(ApplicationSubmissionAnswerRepository.class);
        when(users.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).fullName("Ada Lovelace").email("ada@example.com").build()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(answers.findBySessionIdOrderByCreatedAtAsc(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());

        planner = new FormFillPlanner(
                new FieldClassifier(new QuestionDetectionService()),
                new AnswerResolver(new FieldMappingService(users, profiles, disabledAtsProfiles()), users, answers));
    }

    private static DiscoveredField f(FieldControlType type, String label, boolean required) {
        return new DiscoveredField("#" + label.replaceAll("\\W", ""), type, "", "", label, "", "", "", "",
                required, false, false, false, -1, List.of());
    }

    private static DiscoveredField choice(String label, boolean required, List<String> options) {
        return new DiscoveredField("#" + label.replaceAll("\\W", ""), FieldControlType.SELECT, "", "",
                label, "", "", "", "", required, false, false, false, -1, options);
    }

    private static FormFillPlanner.Documents docs(boolean resume) {
        return new FormFillPlanner.Documents(resume, false, null);
    }

    @Test
    void resolvableFieldsArePlannedWithProvenance() {
        FormFillPlan plan = planner.plan(List.of(
                f(FieldControlType.TEXT, "First Name", true),
                f(FieldControlType.TEXT, "Last Name", true),
                f(FieldControlType.EMAIL, "Email", true)), userId, null, docs(false));

        assertThat(plan.fills()).hasSize(3);
        assertThat(plan.fills()).allSatisfy(fill -> assertThat(fill.source()).isNotBlank());
        assertThat(plan.hasBlockingGaps()).isFalse();
    }

    @Test
    void aRequiredResumeWithNoPackageResumeIsABlockingGap() {
        // The single most important case in this file: without it, an application is submitted with
        // no resume attached and everything reports success.
        FormFillPlan plan = planner.plan(List.of(f(FieldControlType.FILE, "Resume", true)),
                userId, null, docs(false));

        assertThat(plan.fills()).isEmpty();
        assertThat(plan.hasBlockingGaps()).isTrue();
        assertThat(plan.blockingGaps().get(0).reason()).contains("no tailored resume");
    }

    @Test
    void aRequiredResumeWithAPackageResumeIsPlanned() {
        FormFillPlan plan = planner.plan(List.of(f(FieldControlType.FILE, "Resume", true)),
                userId, null, docs(true));
        assertThat(plan.fills()).hasSize(1);
        assertThat(plan.fills().get(0).canonical()).isEqualTo(CanonicalField.RESUME_UPLOAD);
        assertThat(plan.hasBlockingGaps()).isFalse();
    }

    @Test
    void anOptionalUnresolvableFieldIsAGapButNotBlocking() {
        FormFillPlan plan = planner.plan(List.of(f(FieldControlType.TEL, "Phone", false)),
                userId, null, docs(true));
        assertThat(plan.unresolved()).hasSize(1);
        assertThat(plan.hasBlockingGaps()).isFalse();
    }

    @Test
    /**
     * <b>Reason text updated by Phase C</b>, behaviour deliberately unchanged: a required phone
     * field with no verified value must still block. What changed is why — previously the platform
     * had no phone column at all; now it has one that this user has not filled in. The blocking
     * outcome is the part that must never regress, so it is still asserted.
     */
    void aRequiredFieldWithNoVerifiedValueBlocksRatherThanBeingFaked() {
        FormFillPlan plan = planner.plan(List.of(f(FieldControlType.TEL, "Phone", true)),
                userId, null, docs(true));
        assertThat(plan.hasBlockingGaps()).isTrue();
        assertThat(plan.blockingGaps().get(0).reason()).isNotBlank();
        assertThat(plan.fills()).noneMatch(fill -> fill.canonical() == CanonicalField.PHONE);
    }

    @Test
    void hiddenAndDisabledControlsAreNeverWrittenTo() {
        DiscoveredField hidden = new DiscoveredField("#h", FieldControlType.TEXT, "", "", "Email", "", "", "", "",
                false, true, false, false, -1, List.of());
        DiscoveredField disabled = new DiscoveredField("#d", FieldControlType.TEXT, "", "", "Email", "", "", "", "",
                false, false, true, false, -1, List.of());

        FormFillPlan plan = planner.plan(List.of(hidden, disabled), userId, null, docs(true));
        assertThat(plan.fills()).isEmpty();
    }

    @Test
    void aRequiredButUnfillableControlStillSurfacesAsABlockingGap() {
        DiscoveredField readOnly = new DiscoveredField("#r", FieldControlType.TEXT, "", "", "Email", "", "", "", "",
                true, false, false, true, -1, List.of());
        FormFillPlan plan = planner.plan(List.of(readOnly), userId, null, docs(true));
        assertThat(plan.hasBlockingGaps()).isTrue();
        assertThat(plan.blockingGaps().get(0).reason()).contains("not fillable");
    }

    // ── P7 Action 5C-FIX — custom combobox never silently disappears from the plan ──

    /**
     * Test E — a required custom combobox (role=combobox) with no verified answer must be a blocking
     * gap, exactly like any other required-and-unresolved field. Before the fix this control
     * classified UNSUPPORTED and, since discovery's native required/aria-required signals don't
     * always fire on custom widgets, could vanish from the plan entirely with no blocking gap raised.
     */
    @Test
    void aRequiredCustomComboboxWithNoVerifiedAnswerIsABlockingGap() {
        DiscoveredField combobox = f(FieldControlType.COMBOBOX,
                "Will you now or in the future require sponsorship for employment visa status?", true);
        FormFillPlan plan = planner.plan(List.of(combobox), userId, null, docs(true));

        assertThat(plan.fills()).isEmpty();
        assertThat(plan.hasBlockingGaps()).isTrue();
        assertThat(plan.covers(CanonicalField.VISA_SPONSORSHIP)).isTrue();
    }

    /**
     * Test F — an optional custom combobox with no verified answer must remain observable in
     * {@code unresolved()} (and hence in the plan summary / skipped map), never silently dropped and
     * never counted as filled.
     */
    @Test
    void anOptionalCustomComboboxWithNoVerifiedAnswerStaysObservableNeverFilled() {
        DiscoveredField combobox = f(FieldControlType.COMBOBOX,
                "Will you now or in the future require sponsorship for employment visa status?", false);
        FormFillPlan plan = planner.plan(List.of(combobox), userId, null, docs(true));

        assertThat(plan.fills()).isEmpty();
        assertThat(plan.hasBlockingGaps()).isFalse();
        assertThat(plan.unresolved()).hasSize(1);
        assertThat(plan.covers(CanonicalField.VISA_SPONSORSHIP)).isTrue();
    }

    /**
     * Every discovered control must be accounted for in fills()+unresolved() — the structural
     * invariant the HIGH finding violated (14 discovered, 11 planned, 3 silently vanished).
     */
    @Test
    void everyDiscoveredComboboxIsAccountedForInFillsOrUnresolved() {
        DiscoveredField required = f(FieldControlType.COMBOBOX, "Are you legally authorized to work here?", true);
        DiscoveredField optional = f(FieldControlType.COMBOBOX, "How did you hear about this job?", false);
        FormFillPlan plan = planner.plan(List.of(required, optional), userId, null, docs(true));

        assertThat(plan.fills().size() + plan.unresolved().size()).isEqualTo(2);
    }

    /**
     * Test G — WORK_AUTHORIZATION end-to-end: question text classifies to the canonical field, which
     * resolves against a real seeded {@code CandidateAtsProfile.workAuthorization}, and the resolved
     * value reaches the plan with correct provenance. This is a fix-verification, not a fix: reading
     * {@code FieldMappingService}/{@code AtsProfileField}/{@code AnswerResolver} showed the mapping
     * already exists end-to-end (case WORK_AUTHORIZATION -> fromMapping(ctx, "workAuthorization"),
     * backed by AtsProfileField.WORK_AUTHORIZATION) and is already exercised by
     * {@code FieldMappingAtsProfileTest#allFieldsResolve}; the MEDIUM finding from the prior report
     * was a test-harness gap (CandidateAtsProfileService never mocked/seeded there), not a production
     * defect — this test proves that conclusion by seeding the real service and getting a real value.
     */
    @Test
    void workAuthorizationResolvesEndToEndFromASeededAtsProfile() {
        ai.careerpilot.repo.CandidateAtsProfileRepository atsRepo =
                mock(ai.careerpilot.repo.CandidateAtsProfileRepository.class);
        ai.careerpilot.service.profile.ats.CandidateAtsProfileService atsService =
                new ai.careerpilot.service.profile.ats.CandidateAtsProfileService(atsRepo, true);
        when(atsRepo.save(org.mockito.ArgumentMatchers.any(ai.careerpilot.domain.CandidateAtsProfile.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(atsRepo.findByUserId(userId)).thenReturn(Optional.empty());
        ai.careerpilot.domain.CandidateAtsProfile seeded = atsService.update(userId,
                        java.util.Map.of(ai.careerpilot.service.profile.ats.AtsProfileField.WORK_AUTHORIZATION.fieldName(),
                                "Authorized to work without sponsorship"),
                        ai.careerpilot.domain.FieldVerificationSource.USER_ENTERED)
                .orElseThrow();
        when(atsRepo.findByUserId(userId)).thenReturn(Optional.of(seeded));

        FormFillPlanner plannerWithAts = new FormFillPlanner(
                new FieldClassifier(new QuestionDetectionService()),
                new AnswerResolver(new FieldMappingService(users, profiles, atsService), users,
                        mock(ApplicationSubmissionAnswerRepository.class)));

        DiscoveredField workAuthField = f(FieldControlType.COMBOBOX,
                "Are you legally authorized to work in this country?", true);
        FormFillPlan plan = plannerWithAts.plan(List.of(workAuthField), userId, null, docs(true));

        assertThat(plan.hasBlockingGaps()).isFalse();
        assertThat(plan.fills()).hasSize(1);
        assertThat(plan.fills().get(0).canonical()).isEqualTo(CanonicalField.WORK_AUTHORIZATION);
        assertThat(plan.fills().get(0).value()).isEqualTo("Authorized to work without sponsorship");
        assertThat(plan.fills().get(0).source()).isEqualTo("CandidateAtsProfile.workAuthorization");
    }

    // ── choice controls ──

    @Test
    void aDropdownIsOnlyFilledWhenAnOptionGenuinelyMatches() {
        // Refusing beats guessing: on a visa question, picking the closest-looking option is a
        // legally significant fabrication.
        FormFillPlan plan = planner.plan(
                List.of(choice("Do you require visa sponsorship?", false, List.of("Yes", "No"))),
                userId, null, docs(true));
        assertThat(plan.fills()).isEmpty();
        assertThat(plan.unresolved()).hasSize(1);
    }

    @Test
    void optionMatchingIsExactAndNeverSubstringBased() {
        // "No" is a substring of "Not applicable" — on a sponsorship question that difference
        // changes the answer's meaning entirely.
        assertThat(FormFillPlanner.matchOption("No", List.of("Not applicable", "Nope"))).isNull();
        assertThat(FormFillPlanner.matchOption("No", List.of("Not applicable", "No"))).isEqualTo("No");
        assertThat(FormFillPlanner.matchOption("no", List.of("No"))).isEqualTo("No");
    }

    @Test
    void booleanValuesMapOntoYesNoOptions() {
        assertThat(FormFillPlanner.matchOption("true", List.of("Yes", "No"))).isEqualTo("Yes");
        assertThat(FormFillPlanner.matchOption("false", List.of("Yes", "No"))).isEqualTo("No");
        assertThat(FormFillPlanner.matchOption("maybe", List.of("Yes", "No"))).isNull();
    }

    @Test
    void booleanParsingIsStrictAndNeverDefaultsToFalse() {
        assertThat(FormFillPlanner.asBoolean("yes")).isTrue();
        assertThat(FormFillPlanner.asBoolean("N")).isFalse();
        assertThat(FormFillPlanner.asBoolean("possibly")).isNull();
        assertThat(FormFillPlanner.asBoolean(null)).isNull();
    }

    // ── character limits ──

    @Test
    void aValueLongerThanMaxLengthIsTruncatedAtAWordBoundary() {
        DiscoveredField limited = new DiscoveredField("#c", FieldControlType.TEXTAREA, "", "", "Cover letter",
                "", "", "", "", false, false, false, false, 20, List.of());
        String fitted = FormFillPlanner.fitToLimit("the quick brown fox jumps over the lazy dog", limited);
        assertThat(fitted.length()).isLessThanOrEqualTo(20);
        assertThat(fitted).isEqualTo("the quick brown fox");
        // No ellipsis — that would be text the candidate never wrote.
        assertThat(fitted).doesNotContain("…").doesNotContain("...");
    }

    @Test
    void aValueWithinTheLimitIsUntouched() {
        DiscoveredField limited = new DiscoveredField("#c", FieldControlType.TEXTAREA, "", "", "x",
                "", "", "", "", false, false, false, false, 500, List.of());
        assertThat(FormFillPlanner.fitToLimit("short", limited)).isEqualTo("short");
    }

    @Test
    void noDeclaredLimitMeansNoTruncation() {
        DiscoveredField unlimited = new DiscoveredField("#c", FieldControlType.TEXTAREA, "", "", "x",
                "", "", "", "", false, false, false, false, -1, List.of());
        String long_ = "x".repeat(5000);
        assertThat(FormFillPlanner.fitToLimit(long_, unlimited)).hasSize(5000);
    }

    @Test
    void coverLetterTextIsPlannedFromThePackageAndRespectsTheLimit() {
        DiscoveredField cl = new DiscoveredField("#cl", FieldControlType.TEXTAREA, "", "", "Cover Letter",
                "", "", "", "", false, false, false, false, 30, List.of());
        FormFillPlan plan = planner.plan(List.of(cl), userId, null,
                new FormFillPlanner.Documents(false, false, "Dear hiring manager, I am writing to apply."));
        assertThat(plan.fills()).hasSize(1);
        assertThat(plan.fills().get(0).value().length()).isLessThanOrEqualTo(30);
    }

    // ── resilience & reporting ──

    @Test
    void anEmptyFormYieldsAnEmptyPlanRatherThanAnError() {
        assertThat(planner.plan(List.of(), userId, null, docs(true)).isEmpty()).isTrue();
        assertThat(planner.plan(null, userId, null, docs(true)).isEmpty()).isTrue();
    }

    @Test
    void aFailedContextLoadMakesEveryRequiredFieldBlockingRatherThanFillingNothingSilently() {
        when(users.findById(userId)).thenThrow(new IllegalStateException("db down"));
        FormFillPlan plan = planner.plan(List.of(f(FieldControlType.EMAIL, "Email", true)),
                userId, null, docs(true));
        assertThat(plan.fills()).isEmpty();
        assertThat(plan.hasBlockingGaps()).isTrue();
    }

    @Test
    void theSummaryReportsFieldIdentitiesButNeverValues() {
        FormFillPlan plan = planner.plan(List.of(f(FieldControlType.EMAIL, "Email", true)),
                userId, null, docs(true));
        String summary = plan.summary().toString();
        assertThat(summary).contains("EMAIL");
        // The candidate's actual email must never reach a log aggregator via this summary.
        assertThat(summary).doesNotContain("ada@example.com");
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
