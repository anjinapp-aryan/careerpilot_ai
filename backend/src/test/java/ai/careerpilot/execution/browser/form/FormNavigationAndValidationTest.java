package ai.careerpilot.execution.browser.form;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 12C — multi-step navigation, validation detection, and discovery parsing.
 *
 * <p>The navigation tests carry the most weight: clicking "Submit" while believing it said
 * "Continue" delivers a half-filled application to a real employer, irreversibly.
 */
class FormNavigationAndValidationTest {

    private final MultiStepFormNavigator navigator = new MultiStepFormNavigator();
    private final ValidationErrorDetector detector = new ValidationErrorDetector();

    private static MultiStepFormNavigator.Button btn(String label) {
        return new MultiStepFormNavigator.Button("#" + label.replaceAll("\\W", ""), label, true);
    }

    // ── navigation ──

    @Test
    void advanceIsPreferredOverSubmitWhenBothArePresent() {
        // Early pages of a multi-step form often carry a final Submit alongside an active Next;
        // preferring submit there sends an incomplete application.
        MultiStepFormNavigator.Decision d = navigator.decide(List.of(btn("Submit Application"), btn("Next")));
        assertThat(d.action()).isEqualTo(MultiStepFormNavigator.Action.ADVANCE);
        assertThat(d.button().label()).isEqualTo("Next");
    }

    @Test
    void aSingleUnambiguousSubmitIsRecognised() {
        MultiStepFormNavigator.Decision d = navigator.decide(List.of(btn("Submit Application")));
        assertThat(d.action()).isEqualTo(MultiStepFormNavigator.Action.SUBMIT);
    }

    @Test
    void twoSubmitLikeControlsAreAmbiguousAndNeverGuessed() {
        MultiStepFormNavigator.Decision d = navigator.decide(List.of(btn("Submit"), btn("Apply Now")));
        assertThat(d.action()).isEqualTo(MultiStepFormNavigator.Action.UNCLEAR);
        assertThat(d.reason()).contains("ambiguous");
    }

    @Test
    void submitIsNotMatchedInsideALongerWarningLabel() {
        // "Submit" is a substring of "Do not submit without reviewing" — a substring match here
        // clicks the wrong thing on a live form.
        assertThat(navigator.decide(List.of(btn("Do not submit without reviewing"))).action())
                .isEqualTo(MultiStepFormNavigator.Action.UNCLEAR);
    }

    @Test
    void backIsNeverChosen() {
        MultiStepFormNavigator.Decision d = navigator.decide(List.of(btn("Back"), btn("Previous")));
        assertThat(d.action()).isEqualTo(MultiStepFormNavigator.Action.UNCLEAR);
        assertThat(d.reason()).contains("backwards");
    }

    @Test
    void disabledButtonsAreIgnored() {
        MultiStepFormNavigator.Decision d = navigator.decide(List.of(
                new MultiStepFormNavigator.Button("#s", "Submit Application", false)));
        assertThat(d.action()).isEqualTo(MultiStepFormNavigator.Action.UNCLEAR);
    }

    @Test
    void decorativeArrowsAndCasingDoNotDefeatMatching() {
        assertThat(navigator.decide(List.of(btn("Continue →"))).action())
                .isEqualTo(MultiStepFormNavigator.Action.ADVANCE);
        assertThat(navigator.decide(List.of(btn("SAVE AND CONTINUE"))).action())
                .isEqualTo(MultiStepFormNavigator.Action.ADVANCE);
    }

    @Test
    void anEmptyOrUnrecognisedPageIsUnclear() {
        assertThat(navigator.decide(List.of()).action()).isEqualTo(MultiStepFormNavigator.Action.UNCLEAR);
        assertThat(navigator.decide(null).action()).isEqualTo(MultiStepFormNavigator.Action.UNCLEAR);
        assertThat(navigator.decide(List.of(btn("Download brochure"))).action())
                .isEqualTo(MultiStepFormNavigator.Action.UNCLEAR);
    }

    // ── validation ──

    @Test
    void ariaInvalidFieldsAreReported() {
        List<ValidationErrorDetector.ValidationError> errors = detector.detect(
                new ValidationErrorDetector.PostSubmitState(List.of("#email"), List.of(), false));
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).fieldSelector()).isEqualTo("#email");
    }

    @Test
    void recognisedErrorPhrasesAreReportedVerbatim() {
        List<ValidationErrorDetector.ValidationError> errors = detector.detect(
                new ValidationErrorDetector.PostSubmitState(List.of(),
                        List.of("Resume is required", "Thanks for visiting"), false));
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).message()).isEqualTo("Resume is required");
    }

    @Test
    void unrelatedPageTextIsNotMistakenForAnError() {
        assertThat(detector.detect(new ValidationErrorDetector.PostSubmitState(
                List.of(), List.of("We are an equal opportunity employer"), false))).isEmpty();
    }

    @Test
    void aNavigatedPageIsNeverTreatedAsRejectedEvenIfAStaleErrorRegionRemains() {
        // Some ATSes render a residual error region on the confirmation page itself; treating that
        // as a rejection would discard a genuinely successful submission.
        assertThat(detector.wasRejected(new ValidationErrorDetector.PostSubmitState(
                List.of("#email"), List.of("Email is required"), true))).isFalse();
    }

    @Test
    void aNonNavigatedPageWithErrorsIsRejected() {
        assertThat(detector.wasRejected(new ValidationErrorDetector.PostSubmitState(
                List.of(), List.of("This field is required"), false))).isTrue();
    }

    @Test
    void noErrorsIsNotTreatedAsProofOfSuccess() {
        // detect() returning empty means "no evidence of rejection" — the SUBMITTED decision still
        // belongs to VerificationAdjudicator, which this class never short-circuits.
        assertThat(detector.detect(ValidationErrorDetector.PostSubmitState.none())).isEmpty();
        assertThat(detector.wasRejected(null)).isFalse();
    }

    @Test
    void errorsAreMappedBackToTheFieldsTheyImplicate() {
        DiscoveredField email = new DiscoveredField("#email", FieldControlType.EMAIL, "", "", "Email",
                "", "", "", "", true, false, false, false, -1, List.of());
        List<DiscoveredField> implicated = detector.implicatedFields(
                List.of(new ValidationErrorDetector.ValidationError("#email", "required")),
                List.of(email));
        assertThat(implicated).containsExactly(email);
    }

    // ── discovery parsing ──

    /** Map.of caps at 10 pairs; a discovered field has more signals than that. */
    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void discoveryOutputParsesIntoTypedFields() {
        List<DiscoveredField> fields = FormDiscoveryScript.parse(List.of(row(
                "selector", "#first_name", "tag", "input", "type", "text", "contentEditable", false,
                "name", "first_name", "id", "first_name", "label", "First Name", "ariaLabel", "",
                "placeholder", "", "autocomplete", "given-name", "dataAttributes", "",
                "required", true, "hidden", false, "disabled", false, "readOnly", false,
                "maxLength", 50, "options", List.of())));

        assertThat(fields).hasSize(1);
        DiscoveredField f = fields.get(0);
        assertThat(f.controlType()).isEqualTo(FieldControlType.TEXT);
        assertThat(f.required()).isTrue();
        assertThat(f.maxLength()).isEqualTo(50);
        assertThat(f.isFillable()).isTrue();
    }

    @Test
    void oneMalformedControlNeverCostsTheRestOfTheForm() {
        List<DiscoveredField> fields = FormDiscoveryScript.parse(List.of(
                "not a map",
                Map.of("selector", "", "tag", "input"),
                Map.of("selector", "#email", "tag", "input", "type", "email")));
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0).selector()).isEqualTo("#email");
    }

    @Test
    void garbageInputYieldsAnEmptyDiscoveryRatherThanAnException() {
        assertThat(FormDiscoveryScript.parse(null)).isEmpty();
        assertThat(FormDiscoveryScript.parse("nonsense")).isEmpty();
        assertThat(FormDiscoveryScript.parseButtons(null)).isEmpty();
        assertThat(FormDiscoveryScript.parseValidationState(null, false).errorMessages()).isEmpty();
    }

    @Test
    void zeroMaxLengthIsNormalisedToNoLimit() {
        List<DiscoveredField> fields = FormDiscoveryScript.parse(List.of(Map.of(
                "selector", "#x", "tag", "textarea", "maxLength", 0)));
        assertThat(fields.get(0).hasMaxLength()).isFalse();
        assertThat(fields.get(0).maxLength()).isEqualTo(-1);
    }

    @Test
    void selectOptionsSurviveParsing() {
        List<DiscoveredField> fields = FormDiscoveryScript.parse(List.of(Map.of(
                "selector", "#visa", "tag", "select", "label", "Visa sponsorship?",
                "options", List.of("Yes", "No"))));
        assertThat(fields.get(0).options()).containsExactly("Yes", "No");
        assertThat(fields.get(0).controlType()).isEqualTo(FieldControlType.SELECT);
    }

    @Test
    void buttonsParseAndDropUnlabelledOnes() {
        List<MultiStepFormNavigator.Button> buttons = FormDiscoveryScript.parseButtons(List.of(
                Map.of("selector", "#next", "label", "Next", "enabled", true),
                Map.of("selector", "#x", "label", "", "enabled", true)));
        assertThat(buttons).hasSize(1);
        assertThat(buttons.get(0).label()).isEqualTo("Next");
    }

    @Test
    void displayNameFallsBackThroughEveryIdentifyingSignal() {
        assertThat(new DiscoveredField("#s", FieldControlType.TEXT, "", "", "", "", "", "", "",
                false, false, false, false, -1, List.of()).displayName()).isEqualTo("#s");
        assertThat(new DiscoveredField("#s", FieldControlType.TEXT, "nm", "", "", "", "", "", "",
                false, false, false, false, -1, List.of()).displayName()).isEqualTo("nm");
    }
}
