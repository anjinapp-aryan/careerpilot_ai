package ai.careerpilot.execution.browser.validation;

import ai.careerpilot.domain.AtsValidationRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 13A — detects that an employer's page changed under us.
 *
 * <p>An ATS can alter a field's {@code id}, wrap a control in a new widget, or add a required
 * question, and nothing in this platform would notice: the next real submission would simply fill
 * fewer fields and, if the new field is required, abort. <b>Silent degradation is the failure mode
 * this class exists to make impossible.</b>
 *
 * <p>Pure and deterministic — no LLM, no I/O, same discipline as {@code RetryPolicyService},
 * {@code VerificationAdjudicator} and {@code AutomationConfidence}. It compares one run against a
 * baseline built from that posting's own earlier runs.
 *
 * <h2>Why the baseline is per-posting, not per-ATS</h2>
 * Two Greenhouse postings legitimately differ — one asks three questions, another asks twenty.
 * Comparing today's 20-field posting against yesterday's 3-field one would fire a drift alert on
 * every other run and train an operator to ignore it. Like-for-like means the same URL over time.
 *
 * <h2>Why the median, not the last run</h2>
 * A single flaky run — a slow network leaving the page half-rendered — would otherwise become the
 * baseline and mask a real regression on the run after it. The median of prior runs is resistant
 * to exactly that.
 */
@Component
public class SelectorDriftDetector {

    private final int confidenceDropThreshold;
    private final int minimumBaselineRuns;

    public SelectorDriftDetector(
            @Value("${browser.validation.drift.confidence-drop-threshold:10}") int confidenceDropThreshold,
            @Value("${browser.validation.drift.minimum-baseline-runs:2}") int minimumBaselineRuns) {
        this.confidenceDropThreshold = Math.max(1, confidenceDropThreshold);
        this.minimumBaselineRuns = Math.max(1, minimumBaselineRuns);
    }

    public enum Severity {
        /** Not enough history to compare. Explicitly not "no drift" — we do not know yet. */
        NO_BASELINE,
        NONE,
        /** Measurably worse, still usable. */
        WARNING,
        /** Readiness lost, or a required field became unfillable. Stop and investigate. */
        CRITICAL
    }

    /**
     * @param severity   the verdict
     * @param reasons    every observed change, each stated in terms of what actually moved
     * @param baseline   the median confidence of prior runs, or {@code null} when there is none
     * @param current    this run's confidence
     */
    public record DriftReport(Severity severity, List<String> reasons,
                              Integer baselineConfidence, Integer currentConfidence,
                              int baselineRunCount) {

        public DriftReport {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public boolean isAlerting() {
            return severity == Severity.WARNING || severity == Severity.CRITICAL;
        }

        public Map<String, Object> snapshot() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("severity", severity.name());
            out.put("alerting", isAlerting());
            out.put("baselineConfidence", baselineConfidence);
            out.put("currentConfidence", currentConfidence);
            out.put("baselineRunCount", baselineRunCount);
            out.put("reasons", reasons);
            return out;
        }
    }

    /**
     * Compare one run against its own posting's history.
     *
     * @param current  the run just completed
     * @param priorRuns earlier runs for the same URL, newest first, excluding {@code current}
     */
    public DriftReport detect(AtsValidationRun current, List<AtsValidationRun> priorRuns) {
        if (current == null) {
            return new DriftReport(Severity.NO_BASELINE, List.of("no current run to evaluate"), null, null, 0);
        }
        List<AtsValidationRun> baseline = usableBaseline(priorRuns);
        if (baseline.size() < minimumBaselineRuns) {
            return new DriftReport(Severity.NO_BASELINE,
                    List.of("only " + baseline.size() + " prior completed run(s) for this posting — "
                            + minimumBaselineRuns + " needed before drift can be judged"),
                    null, current.getConfidenceScore(), baseline.size());
        }

        int baselineConfidence = medianConfidence(baseline);
        int currentConfidence = nz(current.getConfidenceScore());
        List<String> reasons = new java.util.ArrayList<>();
        Severity severity = Severity.NONE;

        // ── Readiness lost. The most severe signal: this posting used to be automatable and is not.
        boolean baselineReady = majorityReady(baseline);
        if (baselineReady && !Boolean.TRUE.equals(current.getReady())) {
            reasons.add("readiness lost — prior runs were ready, this run is not");
            severity = Severity.CRITICAL;
        }

        // ── A required field we can no longer fill. Directly blocks submission.
        int baselineMissing = medianOf(baseline, r -> nz(r.getMissingRequiredValues()));
        int currentMissing = nz(current.getMissingRequiredValues());
        if (currentMissing > baselineMissing) {
            reasons.add("required fields without a verified value rose from " + baselineMissing
                    + " to " + currentMissing);
            severity = Severity.CRITICAL;
        }

        // ── Confidence drop beyond the threshold.
        int drop = baselineConfidence - currentConfidence;
        if (drop >= confidenceDropThreshold) {
            reasons.add("automation confidence dropped " + drop + " points (baseline "
                    + baselineConfidence + " → " + currentConfidence + ")");
            severity = escalate(severity, Severity.WARNING);
        }

        // ── New unidentified controls. Usually the literal signature of a renamed selector: the
        // control is still there and still drivable, we simply no longer recognise it.
        int baselineUnknown = medianOf(baseline, r -> nz(r.getUnknownControls()));
        int currentUnknown = nz(current.getUnknownControls());
        if (currentUnknown > baselineUnknown) {
            reasons.add("unidentified controls rose from " + baselineUnknown + " to " + currentUnknown
                    + " — a selector or label most likely changed");
            severity = escalate(severity, Severity.WARNING);
        }

        // ── New unsupported controls: the page started using a widget the engine cannot drive.
        int baselineUnsupported = medianOf(baseline, r -> nz(r.getUnsupportedControls()));
        int currentUnsupported = nz(current.getUnsupportedControls());
        if (currentUnsupported > baselineUnsupported) {
            reasons.add("unsupported control types rose from " + baselineUnsupported
                    + " to " + currentUnsupported);
            severity = escalate(severity, Severity.WARNING);
        }

        // ── A CAPTCHA that was not there before changes what is possible on this page at all.
        if (Boolean.TRUE.equals(current.getCaptchaDetected()) && !majorityCaptcha(baseline)) {
            reasons.add("a CAPTCHA appeared on this posting where prior runs had none");
            severity = escalate(severity, Severity.WARNING);
        }

        if (reasons.isEmpty()) {
            reasons = List.of("no measurable change against the last " + baseline.size() + " run(s)");
        }
        return new DriftReport(severity, reasons, baselineConfidence, currentConfidence, baseline.size());
    }

    // ── helpers ──

    /**
     * Only COMPLETED runs form a baseline. A FAILED run has a zero score by construction, and
     * letting it into the median would make a single network blip depress the baseline and then
     * suppress the very alert a genuine regression should raise.
     */
    private static List<AtsValidationRun> usableBaseline(List<AtsValidationRun> priorRuns) {
        if (priorRuns == null) return List.of();
        return priorRuns.stream()
                .filter(r -> r != null && ValidationReport.Status.COMPLETED.name().equals(r.getStatus()))
                .toList();
    }

    private static int medianConfidence(List<AtsValidationRun> runs) {
        return medianOf(runs, r -> nz(r.getConfidenceScore()));
    }

    private static int medianOf(List<AtsValidationRun> runs, java.util.function.ToIntFunction<AtsValidationRun> f) {
        int[] values = runs.stream().mapToInt(f).sorted().toArray();
        if (values.length == 0) return 0;
        int mid = values.length / 2;
        // Even counts take the lower-middle value rather than averaging: these are discrete counts
        // (fields, controls), and "2.5 unknown controls" is not a thing that can be compared against.
        return values.length % 2 == 1 ? values[mid] : values[mid - 1];
    }

    private static boolean majorityReady(List<AtsValidationRun> runs) {
        long ready = runs.stream().filter(r -> Boolean.TRUE.equals(r.getReady())).count();
        return ready * 2 > runs.size();
    }

    private static boolean majorityCaptcha(List<AtsValidationRun> runs) {
        long seen = runs.stream().filter(r -> Boolean.TRUE.equals(r.getCaptchaDetected())).count();
        return seen * 2 > runs.size();
    }

    private static Severity escalate(Severity current, Severity candidate) {
        return current == Severity.CRITICAL ? current : candidate;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
