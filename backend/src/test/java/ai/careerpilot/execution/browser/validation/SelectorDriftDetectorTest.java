package ai.careerpilot.execution.browser.validation;

import ai.careerpilot.domain.AtsValidationRun;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13A — selector drift. The point of this class is that an ATS changing its form can never
 * degrade us silently, so most of these tests assert that a change is <em>noticed</em>, and the
 * rest assert we do not cry wolf.
 */
class SelectorDriftDetectorTest {

    private final SelectorDriftDetector detector = new SelectorDriftDetector(10, 2);

    private static AtsValidationRun run(int confidence, boolean ready) {
        return run(confidence, ready, 0, 0, 0, false);
    }

    private static AtsValidationRun run(int confidence, boolean ready, int unknown,
                                        int unsupported, int missingRequired, boolean captcha) {
        return AtsValidationRun.builder()
                .atsPlatform("GREENHOUSE")
                .url("https://boards.greenhouse.io/acme/jobs/1")
                .urlHash("hash")
                .status(ValidationReport.Status.COMPLETED.name())
                .confidenceScore(confidence)
                .confidenceBand(confidence >= 85 ? "HIGH" : confidence >= 60 ? "MEDIUM" : "LOW")
                .ready(ready)
                .totalControls(20).fillableControls(20).supportedControls(20 - unknown)
                .unsupportedControls(unsupported).unknownControls(unknown)
                .requiredControls(4).mappedControls(20 - unknown)
                .missingRequiredValues(missingRequired)
                .navigationMs(0L).discoveryMs(0L).planningMs(0L).totalMs(0L)
                .iframeCount(0).shadowRootCount(0).captchaDetected(captcha).consoleErrorCount(0)
                .build();
    }

    private static List<AtsValidationRun> baseline(int... confidences) {
        List<AtsValidationRun> runs = new ArrayList<>();
        for (int c : confidences) runs.add(run(c, true));
        return runs;
    }

    // ── the alerts ──

    @Test
    void aConfidenceDropBeyondTheThresholdAlerts() {
        SelectorDriftDetector.DriftReport report = detector.detect(run(80, true), baseline(98, 97, 98));
        assertThat(report.severity()).isEqualTo(SelectorDriftDetector.Severity.WARNING);
        assertThat(report.isAlerting()).isTrue();
        assertThat(report.reasons()).anySatisfy(r -> assertThat(r).contains("confidence dropped"));
        assertThat(report.baselineConfidence()).isEqualTo(98);
    }

    @Test
    void losingReadinessIsCritical() {
        // This posting used to be automatable and is not. Nothing outranks that.
        SelectorDriftDetector.DriftReport report = detector.detect(run(50, false), baseline(98, 97, 98));
        assertThat(report.severity()).isEqualTo(SelectorDriftDetector.Severity.CRITICAL);
        assertThat(report.reasons()).anySatisfy(r -> assertThat(r).contains("readiness lost"));
    }

    @Test
    void aNewlyUnfillableRequiredFieldIsCritical() {
        List<AtsValidationRun> prior = List.of(run(95, true), run(96, true), run(95, true));
        SelectorDriftDetector.DriftReport report =
                detector.detect(run(95, true, 0, 0, 1, false), prior);

        assertThat(report.severity()).isEqualTo(SelectorDriftDetector.Severity.CRITICAL);
        assertThat(report.reasons()).anySatisfy(r -> assertThat(r).contains("required fields"));
    }

    @Test
    void newUnidentifiedControlsAlertEvenWhenConfidenceBarelyMoves() {
        // The literal signature of a renamed selector: the control is still there and still
        // drivable, we just stopped recognising it.
        List<AtsValidationRun> prior = List.of(run(95, true, 0, 0, 0, false), run(95, true, 0, 0, 0, false));
        SelectorDriftDetector.DriftReport report =
                detector.detect(run(93, true, 3, 0, 0, false), prior);

        assertThat(report.isAlerting()).isTrue();
        assertThat(report.reasons()).anySatisfy(r -> assertThat(r).contains("selector or label"));
    }

    @Test
    void aNewUnsupportedControlTypeAlerts() {
        List<AtsValidationRun> prior = List.of(run(95, true, 0, 0, 0, false), run(95, true, 0, 0, 0, false));
        SelectorDriftDetector.DriftReport report =
                detector.detect(run(95, true, 0, 2, 0, false), prior);
        assertThat(report.reasons()).anySatisfy(r -> assertThat(r).contains("unsupported control types"));
    }

    @Test
    void aCaptchaAppearingWhereThereWasNoneAlerts() {
        List<AtsValidationRun> prior = List.of(run(95, true, 0, 0, 0, false), run(95, true, 0, 0, 0, false));
        SelectorDriftDetector.DriftReport report =
                detector.detect(run(95, true, 0, 0, 0, true), prior);
        assertThat(report.reasons()).anySatisfy(r -> assertThat(r).contains("CAPTCHA appeared"));
    }

    @Test
    void criticalIsNeverDowngradedByALaterWarning() {
        List<AtsValidationRun> prior = List.of(run(95, true, 0, 0, 0, false), run(95, true, 0, 0, 0, false));
        // Readiness lost (critical) AND new unknown controls (warning) in the same run.
        SelectorDriftDetector.DriftReport report =
                detector.detect(run(40, false, 5, 0, 0, false), prior);
        assertThat(report.severity()).isEqualTo(SelectorDriftDetector.Severity.CRITICAL);
    }

    // ── not crying wolf ──

    @Test
    void aStablePostingReportsNoDrift() {
        SelectorDriftDetector.DriftReport report = detector.detect(run(97, true), baseline(98, 97, 98));
        assertThat(report.severity()).isEqualTo(SelectorDriftDetector.Severity.NONE);
        assertThat(report.isAlerting()).isFalse();
    }

    @Test
    void animprovementIsNeverAnAlert() {
        SelectorDriftDetector.DriftReport report = detector.detect(run(100, true), baseline(80, 82, 81));
        assertThat(report.isAlerting()).isFalse();
    }

    @Test
    void aDropBelowTheThresholdIsNotAnAlert() {
        // 5 points on a 10-point threshold: real forms vary a little run to run.
        SelectorDriftDetector.DriftReport report = detector.detect(run(93, true), baseline(98, 98, 98));
        assertThat(report.isAlerting()).isFalse();
    }

    @Test
    void tooLittleHistoryReportsNoBaselineRatherThanNoDrift() {
        // The distinction matters: "we have not checked" must never render as "all clear".
        SelectorDriftDetector.DriftReport report = detector.detect(run(95, true), List.of(run(98, true)));
        assertThat(report.severity()).isEqualTo(SelectorDriftDetector.Severity.NO_BASELINE);
        assertThat(report.isAlerting()).isFalse();
        assertThat(report.baselineConfidence()).isNull();
        assertThat(report.reasons()).anySatisfy(r -> assertThat(r).contains("needed before drift"));
    }

    @Test
    void noHistoryAtAllReportsNoBaseline() {
        assertThat(detector.detect(run(95, true), List.of()).severity())
                .isEqualTo(SelectorDriftDetector.Severity.NO_BASELINE);
        assertThat(detector.detect(run(95, true), null).severity())
                .isEqualTo(SelectorDriftDetector.Severity.NO_BASELINE);
    }

    /**
     * A single flaky run must not become the baseline and mask the real regression after it. The
     * median is what makes that true; a mean or a last-run comparison would not.
     */
    @Test
    void oneFlakyRunDoesNotPoisonTheBaseline() {
        List<AtsValidationRun> prior = baseline(98, 20, 98, 97);
        SelectorDriftDetector.DriftReport report = detector.detect(run(97, true), prior);
        assertThat(report.baselineConfidence()).isGreaterThan(90);
        assertThat(report.isAlerting()).isFalse();
    }

    @Test
    void failedRunsAreExcludedFromTheBaseline() {
        // A FAILED run scores 0 by construction; letting it in would depress the baseline and
        // suppress the alert a genuine regression should raise.
        AtsValidationRun failed = run(0, false);
        failed.setStatus(ValidationReport.Status.FAILED.name());
        List<AtsValidationRun> prior = List.of(failed, run(98, true), run(98, true));

        SelectorDriftDetector.DriftReport report = detector.detect(run(80, true), prior);
        assertThat(report.baselineRunCount()).isEqualTo(2);
        assertThat(report.baselineConfidence()).isEqualTo(98);
        assertThat(report.isAlerting()).isTrue();
    }

    @Test
    void aNullCurrentRunIsHandledWithoutThrowing() {
        assertThat(detector.detect(null, baseline(98, 98)).severity())
                .isEqualTo(SelectorDriftDetector.Severity.NO_BASELINE);
    }

    @Test
    void theThresholdIsConfigurable() {
        SelectorDriftDetector strict = new SelectorDriftDetector(3, 2);
        assertThat(strict.detect(run(94, true), baseline(98, 98)).isAlerting()).isTrue();
    }

    @Test
    void theSnapshotCarriesBothSidesOfTheComparison() {
        var snapshot = detector.detect(run(80, true), baseline(98, 98)).snapshot();
        assertThat(snapshot).containsEntry("baselineConfidence", 98)
                .containsEntry("currentConfidence", 80)
                .containsEntry("alerting", true);
    }
}
