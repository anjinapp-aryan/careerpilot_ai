package ai.careerpilot.execution.browser.validation;

import ai.careerpilot.execution.browser.form.CanonicalField;
import ai.careerpilot.execution.browser.form.DiscoveredField;
import ai.careerpilot.execution.browser.form.FieldControlType;
import ai.careerpilot.execution.browser.form.FormFillPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 12C.5 — deterministic coverage metrics and the confidence score derived from them. */
class AutomationConfidenceTest {

    private static DiscoveredField field(FieldControlType type, String label, boolean required) {
        return new DiscoveredField("#" + label.replaceAll("\\W", ""), type, "", "", label,
                "", "", "", "", required, false, false, false, -1, List.of());
    }

    private static DiscoveredField hidden(String label) {
        return new DiscoveredField("#" + label, FieldControlType.TEXT, "", "", label,
                "", "", "", "", false, true, false, false, -1, List.of());
    }

    private static FormFillPlan plan(List<DiscoveredField> filled, List<DiscoveredField> blockedRequired) {
        List<FormFillPlan.PlannedFill> fills = filled.stream()
                .map(f -> new FormFillPlan.PlannedFill(f, CanonicalField.EMAIL, "v", "src")).toList();
        List<FormFillPlan.UnresolvedField> unresolved = blockedRequired.stream()
                .map(f -> new FormFillPlan.UnresolvedField(f, CanonicalField.RESUME_UPLOAD, "no resume", true))
                .toList();
        return new FormFillPlan(fills, unresolved);
    }

    // ── the cap that matters ──

    /**
     * The single most important assertion in this file. A page with many perfectly mapped fields and
     * one unfillable required resume must not read HIGH — that is exactly the false confidence the
     * whole browser-automation series exists to prevent.
     */
    @Test
    void oneMissingRequiredValueCapsConfidenceAtLowHoweverGoodTheRest() {
        List<DiscoveredField> good = List.of(
                field(FieldControlType.TEXT, "First Name", true),
                field(FieldControlType.TEXT, "Last Name", true),
                field(FieldControlType.EMAIL, "Email", true));
        DiscoveredField resume = field(FieldControlType.FILE, "Resume", true);

        List<DiscoveredField> all = new java.util.ArrayList<>(good);
        all.add(resume);
        SelectorCoverage coverage = SelectorCoverage.from(all,
                List.of(CanonicalField.FIRST_NAME, CanonicalField.LAST_NAME, CanonicalField.EMAIL,
                        CanonicalField.RESUME_UPLOAD),
                plan(good, List.of(resume)));

        AutomationConfidence confidence = AutomationConfidence.from(coverage);
        assertThat(confidence.band()).isEqualTo(AutomationConfidence.Band.LOW);
        assertThat(confidence.ready()).isFalse();
        assertThat(confidence.rationale()).contains("capped at LOW");
    }

    @Test
    void aFullyMappedFormIsHighAndReady() {
        List<DiscoveredField> all = List.of(
                field(FieldControlType.TEXT, "First Name", true),
                field(FieldControlType.TEXT, "Last Name", true),
                field(FieldControlType.EMAIL, "Email", true));
        SelectorCoverage coverage = SelectorCoverage.from(all,
                List.of(CanonicalField.FIRST_NAME, CanonicalField.LAST_NAME, CanonicalField.EMAIL),
                plan(all, List.of()));

        AutomationConfidence confidence = AutomationConfidence.from(coverage);
        assertThat(confidence.score()).isEqualTo(100);
        assertThat(confidence.band()).isEqualTo(AutomationConfidence.Band.HIGH);
        assertThat(confidence.ready()).isTrue();
    }

    @Test
    void manyUnidentifiedControlsDragConfidenceDownWithoutBlockingReadiness() {
        List<DiscoveredField> all = new java.util.ArrayList<>();
        all.add(field(FieldControlType.EMAIL, "Email", false));
        for (int i = 0; i < 9; i++) all.add(field(FieldControlType.TEXT, "Mystery " + i, false));

        List<CanonicalField> classified = new java.util.ArrayList<>();
        classified.add(CanonicalField.EMAIL);
        for (int i = 0; i < 9; i++) classified.add(CanonicalField.UNKNOWN);

        SelectorCoverage coverage = SelectorCoverage.from(all, classified,
                plan(List.of(all.get(0)), List.of()));
        AutomationConfidence confidence = AutomationConfidence.from(coverage);

        assertThat(coverage.unknownControls()).isEqualTo(9);
        assertThat(confidence.band()).isNotEqualTo(AutomationConfidence.Band.HIGH);
        // No required field is missing, so this is incompleteness, not unviability.
        assertThat(confidence.score()).isBetween(60, 90);
    }

    // ── honesty about what a metric does and does not prove ──

    @Test
    void aPageDeclaringNoRequiredFieldsSaysItsCoverageIsUnprovenNotSatisfied() {
        List<DiscoveredField> all = List.of(field(FieldControlType.EMAIL, "Email", false));
        SelectorCoverage coverage = SelectorCoverage.from(all, List.of(CanonicalField.EMAIL),
                plan(all, List.of()));
        AutomationConfidence confidence = AutomationConfidence.from(coverage);

        assertThat(coverage.requiredControls()).isZero();
        assertThat(confidence.rationale()).contains("unproven rather than satisfied");
    }

    @Test
    void anEmptyPageScoresZeroRatherThanPerfect() {
        AutomationConfidence confidence = AutomationConfidence.from(SelectorCoverage.empty());
        assertThat(confidence.score()).isZero();
        assertThat(confidence.band()).isEqualTo(AutomationConfidence.Band.LOW);
        assertThat(confidence.ready()).isFalse();
    }

    @Test
    void nullCoverageIsHandledWithoutThrowing() {
        assertThat(AutomationConfidence.from(null).ready()).isFalse();
    }

    // ── coverage arithmetic ──

    @Test
    void hiddenControlsCountAsDiscoveredButNotAsCoverageFailures() {
        List<DiscoveredField> all = List.of(
                field(FieldControlType.EMAIL, "Email", false), hidden("csrf"));
        SelectorCoverage coverage = SelectorCoverage.from(all,
                List.of(CanonicalField.EMAIL, CanonicalField.UNKNOWN),
                plan(List.of(all.get(0)), List.of()));

        assertThat(coverage.totalControls()).isEqualTo(2);
        assertThat(coverage.fillableControls()).isEqualTo(1);
        // The hidden field must not be counted as "unidentified" — it was never ours to identify.
        assertThat(coverage.unknownControls()).isZero();
        assertThat(coverage.classificationCoverage()).isEqualTo(1.0);
    }

    @Test
    void unsupportedControlsAreCountedSeparatelyFromUnidentifiedOnes() {
        // The distinction is what tells an operator whether the fix is engine work or one
        // classifier rule.
        List<DiscoveredField> all = List.of(
                field(FieldControlType.UNSUPPORTED, "Custom widget", false),
                field(FieldControlType.TEXT, "Mystery", false));
        SelectorCoverage coverage = SelectorCoverage.from(all,
                List.of(CanonicalField.UNKNOWN, CanonicalField.UNKNOWN),
                plan(List.of(), List.of()));

        assertThat(coverage.unsupportedControls()).isEqualTo(1);
        assertThat(coverage.unknownControls()).isEqualTo(1);
    }

    @Test
    void categoryDistributionCountsEachCanonicalField() {
        List<DiscoveredField> all = List.of(
                field(FieldControlType.EMAIL, "Email", false),
                field(FieldControlType.TEXT, "Mystery", false));
        SelectorCoverage coverage = SelectorCoverage.from(all,
                List.of(CanonicalField.EMAIL, CanonicalField.UNKNOWN), plan(List.of(), List.of()));

        assertThat(coverage.categoryDistribution())
                .containsEntry("EMAIL", 1).containsEntry("UNKNOWN", 1);
    }

    @Test
    void emptyDiscoveryYieldsEmptyCoverage() {
        assertThat(SelectorCoverage.from(List.of(), List.of(), null).totalControls()).isZero();
        assertThat(SelectorCoverage.from(null, null, null).totalControls()).isZero();
    }

    @Test
    void confidenceScoreIsAlwaysWithinRange() {
        for (int required = 0; required < 5; required++) {
            for (int missing = 0; missing <= required; missing++) {
                SelectorCoverage coverage = new SelectorCoverage(10, 8, 6, 2, 2,
                        required, 6, missing, java.util.Map.of());
                int score = AutomationConfidence.from(coverage).score();
                assertThat(score).isBetween(0, 100);
            }
        }
    }
}
