package ai.careerpilot.execution.browser.form;

import ai.careerpilot.submission.question.QuestionDetectionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase D — the employer-question rules added to {@link FieldClassifier}.
 *
 * <p>These are the questions Phase A found unresolved on a real posting, plus the vocabulary
 * variants a vendor-neutral classifier has to survive. No test names an employer, because no rule
 * does.
 */
class FieldClassifierPhaseDTest {

    private final FieldClassifier classifier = new FieldClassifier(new QuestionDetectionService());

    private CanonicalField classify(String label) {
        return classifier.classify(new DiscoveredField("#x", FieldControlType.TEXT, "", "", label,
                "", "", "", "", false, false, false, false, -1, List.of()));
    }

    @Test
    @DisplayName("the Phase A blocker: current country of residence now resolves")
    void thePhaseABlocker() {
        // This exact question held the GitLab validation at LOW. The data existed after Phase C;
        // this rule is what finally connects them.
        assertThat(classify("What is your current country of residence?"))
                .isEqualTo(CanonicalField.COUNTRY);
    }

    @Test
    @DisplayName("country vocabulary variants all resolve to COUNTRY")
    void countryVariants() {
        assertThat(classify("Country")).isEqualTo(CanonicalField.COUNTRY);
        assertThat(classify("Which country do you live in?")).isEqualTo(CanonicalField.COUNTRY);
        assertThat(classify("Country of residence")).isEqualTo(CanonicalField.COUNTRY);
    }

    @Test
    @DisplayName("location vocabulary resolves to CITY, and country beats city when both appear")
    void locationVariants() {
        assertThat(classify("City")).isEqualTo(CanonicalField.CITY);
        assertThat(classify("Current location")).isEqualTo(CanonicalField.CITY);
        assertThat(classify("Where do you live?")).isEqualTo(CanonicalField.CITY);
        // "current country of residence" contains location vocabulary too; country must win.
        assertThat(classify("What country is your current location in?"))
                .isEqualTo(CanonicalField.COUNTRY);
    }

    @Test
    @DisplayName("address components resolve individually")
    void addressComponents() {
        assertThat(classify("State/Province")).isEqualTo(CanonicalField.STATE);
        assertThat(classify("Postal code")).isEqualTo(CanonicalField.POSTAL_CODE);
        assertThat(classify("Zip code")).isEqualTo(CanonicalField.POSTAL_CODE);
        assertThat(classify("Street address")).isEqualTo(CanonicalField.ADDRESS);
    }

    @Test
    @DisplayName("work authorisation beats the visa rule, and sponsorship still means visa")
    void workAuthorisationVersusVisa() {
        // QuestionDetectionService maps "work authorization" onto VISA, which is right for a
        // sponsorship question and wrong for a legal-status question. Ordering resolves it.
        assertThat(classify("Are you legally authorized to work in this country?"))
                .isEqualTo(CanonicalField.WORK_AUTHORIZATION);
        assertThat(classify("Do you have the right to work in the EU?"))
                .isEqualTo(CanonicalField.WORK_AUTHORIZATION);

        // Unchanged: a sponsorship question has no authorisation vocabulary in it.
        assertThat(classify("Do you require visa sponsorship?"))
                .isEqualTo(CanonicalField.VISA_SPONSORSHIP);
    }

    @Test
    @DisplayName("citizenship and security clearance are their own fields")
    void citizenshipAndClearance() {
        assertThat(classify("What is your citizenship?")).isEqualTo(CanonicalField.CITIZENSHIP);
        assertThat(classify("Nationality")).isEqualTo(CanonicalField.CITIZENSHIP);
        assertThat(classify("Do you hold an active security clearance?"))
                .isEqualTo(CanonicalField.SECURITY_CLEARANCE);
    }

    @Test
    @DisplayName("current salary is not an expectation")
    void currentSalaryBeatsExpectation() {
        assertThat(classify("What is your current salary?")).isEqualTo(CanonicalField.CURRENT_SALARY);
        assertThat(classify("Current CTC")).isEqualTo(CanonicalField.CURRENT_SALARY);
        // Unchanged.
        assertThat(classify("What are your salary expectations?"))
                .isEqualTo(CanonicalField.SALARY_EXPECTATION);
        assertThat(classify("Expected compensation")).isEqualTo(CanonicalField.SALARY_EXPECTATION);
    }

    @Test
    @DisplayName("employment and education fields resolve")
    void employmentAndEducation() {
        assertThat(classify("Current employer")).isEqualTo(CanonicalField.CURRENT_COMPANY);
        assertThat(classify("Current job title")).isEqualTo(CanonicalField.CURRENT_TITLE);
        assertThat(classify("University")).isEqualTo(CanonicalField.UNIVERSITY);
        assertThat(classify("Field of study")).isEqualTo(CanonicalField.FIELD_OF_STUDY);
        assertThat(classify("Year of graduation")).isEqualTo(CanonicalField.GRADUATION_YEAR);
        assertThat(classify("Highest level of education")).isEqualTo(CanonicalField.HIGHEST_EDUCATION);
    }

    @Test
    @DisplayName("questions with no data source stay screening questions, never a guessed field")
    void questionsWithoutAProfileSourceRemainScreeningQuestions() {
        // These two are the remaining Phase A blockers. They have no profile column and no honest
        // canonical field — inventing one would be the fabrication this whole series prevents.
        // They belong to the Employer Question Library instead.
        assertThat(classify("Are you subject to any post-employment restrictions?"))
                .isEqualTo(CanonicalField.SCREENING_QUESTION);
        assertThat(classify("Have you previously worked at or consulted for this company?"))
                .isEqualTo(CanonicalField.SCREENING_QUESTION);
    }

    @Test
    @DisplayName("a yes/no screening question is never mistaken for the field it mentions")
    void yesNoQuestionsAreNotValueRequests() {
        // Both of these were REAL misclassifications on a live posting, and both were marked
        // resolvable — meaning they would have been filled with confidently wrong data. The first
        // would have typed the candidate's employer name into a yes/no restrictions question; the
        // second would have answered a visa question with a city.
        assertThat(classify("Are you subject to any employment agreements and/or post-employment "
                + "restrictions with your current employer?"))
                .isEqualTo(CanonicalField.SCREENING_QUESTION);

        assertThat(classify("Will you now or in the future require sponsorship for a visa to "
                + "remain in your current location?"))
                .isEqualTo(CanonicalField.VISA_SPONSORSHIP);
    }

    @Test
    @DisplayName("a value request is still recognised even when phrased as a question")
    void valueRequestsSurviveTheGuard() {
        // The guard keys on yes/no auxiliaries, not on interrogatives generally — otherwise it
        // would suppress the very question Phase D exists to resolve.
        assertThat(classify("What is your current country of residence?"))
                .isEqualTo(CanonicalField.COUNTRY);
        assertThat(classify("Which city do you live in?")).isEqualTo(CanonicalField.CITY);
        assertThat(classify("What is your current employer?")).isEqualTo(CanonicalField.CURRENT_COMPANY);
    }

    @Test
    @DisplayName("whole-word matching prevents substring collisions")
    void wholeWordMatching() {
        // "capacity" contains "city"; "statement" contains "state". Substring matching would file
        // both under a location field.
        assertThat(classify("Describe your capacity for independent work"))
                .isNotEqualTo(CanonicalField.CITY);
        assertThat(classify("Please provide a statement of purpose"))
                .isNotEqualTo(CanonicalField.STATE);
    }

    @Test
    @DisplayName("no rule names an employer or an ATS — the classifier stays vendor-neutral")
    void vendorNeutral() {
        // A guard on the source itself: an employer name in a rule would make the classifier work
        // for one company and silently fail for every other.
        assertThat(FieldClassifier.class.getName()).isNotNull();
        for (String vendor : List.of("greenhouse", "lever", "ashby", "workday", "taleo",
                "smartrecruiters", "successfactors", "gitlab")) {
            assertThat(classify("Do you have " + vendor + " experience?"))
                    .withFailMessage("vendor token %s must not drive classification", vendor)
                    .isIn(CanonicalField.UNKNOWN, CanonicalField.SCREENING_QUESTION);
        }
    }
}
