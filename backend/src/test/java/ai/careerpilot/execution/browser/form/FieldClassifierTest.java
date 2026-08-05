package ai.careerpilot.execution.browser.form;

import ai.careerpilot.submission.question.QuestionDetectionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 12C — field classification. The tests that matter most here are the <em>collisions</em>:
 * the cases where a naive substring match attaches the wrong document or the wrong answer to a real
 * employer's form.
 */
class FieldClassifierTest {

    private final FieldClassifier classifier = new FieldClassifier(new QuestionDetectionService());

    private static DiscoveredField field(FieldControlType type, String label) {
        return new DiscoveredField("#x", type, "", "", label, "", "", "", "",
                false, false, false, false, -1, List.of());
    }

    private static DiscoveredField field(FieldControlType type, String label, String name,
                                         String id, String autocomplete) {
        return new DiscoveredField("#x", type, name, id, label, "", "", autocomplete, "",
                false, false, false, false, -1, List.of());
    }

    // ── the collisions ──

    @Test
    void coverLetterFileBeatsTheGenericResumeRule() {
        // A naive "is this a file input? then it's the resume" rule uploads the cover letter into
        // the resume slot — the employer then sees no resume at all.
        assertThat(classifier.classify(field(FieldControlType.FILE, "Cover Letter")))
                .isEqualTo(CanonicalField.COVER_LETTER_UPLOAD);
        assertThat(classifier.classify(field(FieldControlType.FILE, "Resume/CV")))
                .isEqualTo(CanonicalField.RESUME_UPLOAD);
    }

    @Test
    void anUnlabelledFileInputIsNeverAssumedToBeTheResume() {
        assertThat(classifier.classify(field(FieldControlType.FILE, "")))
                .isEqualTo(CanonicalField.UNKNOWN);
    }

    @Test
    void cvIsMatchedAsAWholeWordSoCovidQuestionsAreNotResumes() {
        assertThat(classifier.classify(field(FieldControlType.FILE, "Have you received a COVID vaccination?")))
                .isEqualTo(CanonicalField.UNKNOWN);
    }

    @Test
    void firstAndLastNameBeatTheGenericNameRule() {
        assertThat(classifier.classify(field(FieldControlType.TEXT, "First Name")))
                .isEqualTo(CanonicalField.FIRST_NAME);
        assertThat(classifier.classify(field(FieldControlType.TEXT, "Last Name")))
                .isEqualTo(CanonicalField.LAST_NAME);
        assertThat(classifier.classify(field(FieldControlType.TEXT, "Full Name")))
                .isEqualTo(CanonicalField.FULL_NAME);
    }

    @Test
    void linkedInBeatsTheGenericUrlRule() {
        assertThat(classifier.classify(field(FieldControlType.TEXT, "LinkedIn Profile URL")))
                .isEqualTo(CanonicalField.LINKEDIN_URL);
        assertThat(classifier.classify(field(FieldControlType.TEXT, "GitHub URL")))
                .isEqualTo(CanonicalField.GITHUB_URL);
    }

    // ── portability: the same meaning, expressed the way different ATSes express it ──

    @Test
    void autocompleteTokensAreHonouredWhenLabelsAreAbsent() {
        assertThat(classifier.classify(field(FieldControlType.TEXT, "", "", "", "given-name")))
                .isEqualTo(CanonicalField.FIRST_NAME);
        assertThat(classifier.classify(field(FieldControlType.TEXT, "", "", "", "family-name")))
                .isEqualTo(CanonicalField.LAST_NAME);
        // Token lists, as browsers actually emit them.
        assertThat(classifier.classify(field(FieldControlType.TEXT, "", "", "", "shipping given-name")))
                .isEqualTo(CanonicalField.FIRST_NAME);
    }

    @Test
    void theSameFieldIsRecognisedFromLabelNameOrIdAlone() {
        assertThat(classifier.classify(field(FieldControlType.TEXT, "Email")))
                .isEqualTo(CanonicalField.EMAIL);
        assertThat(classifier.classify(field(FieldControlType.TEXT, "", "email", "", "")))
                .isEqualTo(CanonicalField.EMAIL);
        assertThat(classifier.classify(field(FieldControlType.TEXT, "", "", "email", "")))
                .isEqualTo(CanonicalField.EMAIL);
    }

    @Test
    void controlTypeAloneIdentifiesEmailAndPhone() {
        assertThat(classifier.classify(field(FieldControlType.EMAIL, "Contact"))).isEqualTo(CanonicalField.EMAIL);
        assertThat(classifier.classify(field(FieldControlType.TEL, "Contact"))).isEqualTo(CanonicalField.PHONE);
    }

    @Test
    void differentAtsWordingsForSalaryReachTheSameCanonicalField() {
        assertThat(classifier.classify(field(FieldControlType.TEXT, "What are your salary expectations?")))
                .isEqualTo(CanonicalField.SALARY_EXPECTATION);
        assertThat(classifier.classify(field(FieldControlType.TEXT, "Expected compensation")))
                .isEqualTo(CanonicalField.SALARY_EXPECTATION);
    }

    @Test
    void visaAndRelocationAndRemoteAreRecognised() {
        assertThat(classifier.classify(field(FieldControlType.SELECT, "Do you require visa sponsorship?")))
                .isEqualTo(CanonicalField.VISA_SPONSORSHIP);
        assertThat(classifier.classify(field(FieldControlType.SELECT, "Are you willing to relocate?")))
                .isEqualTo(CanonicalField.RELOCATION);
        assertThat(classifier.classify(field(FieldControlType.SELECT, "What is your remote work preference?")))
                .isEqualTo(CanonicalField.REMOTE_PREFERENCE);
        assertThat(classifier.classify(field(FieldControlType.TEXT, "Notice period")))
                .isEqualTo(CanonicalField.NOTICE_PERIOD);
    }

    // ── screening questions ──

    @Test
    void aLongQuestionOnATextareaIsAScreeningQuestion() {
        assertThat(classifier.classify(field(FieldControlType.TEXTAREA, "Why do you want to work here?")))
                .isEqualTo(CanonicalField.SCREENING_QUESTION);
        assertThat(classifier.classify(field(FieldControlType.TEXTAREA,
                "Describe a challenging project you delivered recently")))
                .isEqualTo(CanonicalField.SCREENING_QUESTION);
    }

    @Test
    /**
     * <b>Updated by Phase D</b>, and the underlying invariant is unchanged. "City" was {@code
     * UNKNOWN} only because no rule existed for it, not because {@code UNKNOWN} was correct — the
     * real point was that a short field label must never become a {@code SCREENING_QUESTION} and
     * receive a paragraph of prose. Phase D gives it its own field; the guarantee that matters is
     * still asserted, and now for a label that genuinely has no rule.
     */
    void aShortFieldLabelIsNeverTreatedAsAProseQuestion() {
        assertThat(classifier.classify(field(FieldControlType.TEXT, "City")))
                .isEqualTo(CanonicalField.CITY);

        // A short label with no matching rule stays UNKNOWN rather than collecting prose.
        assertThat(classifier.classify(field(FieldControlType.TEXT, "Badge")))
                .isEqualTo(CanonicalField.UNKNOWN);
        assertThat(classifier.classify(field(FieldControlType.TEXT, "Employee referral code")))
                .isEqualTo(CanonicalField.UNKNOWN);
    }

    @Test
    void aCheckboxWithAnUnrecognisedLabelIsNeverAScreeningQuestion() {
        // Only textual controls can hold a prose answer; a checkbox cannot.
        assertThat(classifier.classify(field(FieldControlType.CHECKBOX,
                "I agree to the terms and conditions of this application process")))
                .isEqualTo(CanonicalField.UNKNOWN);
    }

    @Test
    void anUnrecognisedFieldIsUnknownAndNeverGuessed() {
        assertThat(classifier.classify(field(FieldControlType.TEXT, "Employee referral code")))
                .isEqualTo(CanonicalField.UNKNOWN);
        assertThat(classifier.classify(null)).isEqualTo(CanonicalField.UNKNOWN);
    }

    @Test
    void unsupportedControlsAreNeverClassifiedAsFillableFields() {
        assertThat(classifier.classify(field(FieldControlType.UNSUPPORTED, "Email")))
                .isEqualTo(CanonicalField.EMAIL);
        // Classification identifies meaning; fillability is DiscoveredField's job, and it refuses.
        assertThat(new DiscoveredField("#x", FieldControlType.UNSUPPORTED, "", "", "Email", "", "", "", "",
                false, false, false, false, -1, List.of()).isFillable()).isFalse();
    }

    @Test
    /**
     * <b>Deliberately inverted by Phase C.</b> PHONE and LINKEDIN_URL previously reported "no data
     * source" because the schema had no column for them. Both are now backed by
     * {@code candidate_ats_profile}, so the set is empty — asserted exhaustively, since a field
     * silently rejoining it would mean a canonical field with nothing behind it.
     */
    void noCanonicalFieldIsWithoutADataSourceAnyMore() {
        assertThat(CanonicalField.PHONE.hasNoDataSource()).isFalse();
        assertThat(CanonicalField.LINKEDIN_URL.hasNoDataSource()).isFalse();
        assertThat(CanonicalField.EMAIL.hasNoDataSource()).isFalse();

        for (CanonicalField field : CanonicalField.values()) {
            assertThat(field.hasNoDataSource())
                    .withFailMessage("%s reports no data source", field).isFalse();
        }
    }

    @Test
    void controlTypeMappingFollowsTheHtmlSpecForUnknownTypes() {
        // The spec requires an unknown input type to behave as text — that is the browser's own
        // behaviour, not a guess on our part.
        assertThat(FieldControlType.from("input", "definitely-not-real", false)).isEqualTo(FieldControlType.TEXT);
        assertThat(FieldControlType.from("input", "submit", false)).isEqualTo(FieldControlType.UNSUPPORTED);
        assertThat(FieldControlType.from("div", "", true)).isEqualTo(FieldControlType.RICH_TEXT);
        assertThat(FieldControlType.from("select", "", false)).isEqualTo(FieldControlType.SELECT);
        assertThat(FieldControlType.from("div", "", false)).isEqualTo(FieldControlType.UNSUPPORTED);
    }
}
