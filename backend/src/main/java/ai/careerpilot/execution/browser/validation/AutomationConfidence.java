package ai.careerpilot.execution.browser.validation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 12C.5 — a deterministic readiness score for one validated page. No LLM, no heuristic
 * tuning against a page we happened to test — the same discipline as {@code RetryPolicyService},
 * {@code VerificationAdjudicator} and {@code RecommendedActionEngine}.
 *
 * <p><b>The weighting encodes what actually stops an application from succeeding.</b> Required-field
 * coverage dominates at 60 points because a single unfillable required field means the form cannot
 * be submitted at all, however well the other forty are handled. Classification coverage is 25 —
 * an unidentified optional field costs completeness, not viability. Unsupported controls are 15,
 * the least severe because they are usually decorative or optional widgets.
 *
 * <p><b>A missing required value caps the band at LOW regardless of score.</b> Without that, a page
 * with 40 perfectly mapped fields and one unfillable required resume would score ~85 and read HIGH,
 * which is the exact false confidence Phase 12C exists to prevent.
 */
public record AutomationConfidence(int score, Band band, boolean ready, String rationale) {

    public enum Band { HIGH, MEDIUM, LOW }

    private static final double WEIGHT_REQUIRED = 60.0;
    private static final double WEIGHT_CLASSIFICATION = 25.0;
    private static final double WEIGHT_SUPPORTED = 15.0;

    private static final int HIGH_THRESHOLD = 85;
    private static final int MEDIUM_THRESHOLD = 60;

    public static AutomationConfidence none(String reason) {
        return new AutomationConfidence(0, Band.LOW, false, reason);
    }

    public static AutomationConfidence from(SelectorCoverage coverage) {
        if (coverage == null || coverage.totalControls() == 0) {
            return none("no controls discovered — nothing to score");
        }

        double raw = WEIGHT_REQUIRED * coverage.requiredCoverage()
                + WEIGHT_CLASSIFICATION * coverage.classificationCoverage()
                + WEIGHT_SUPPORTED * (1.0 - coverage.unsupportedRatio());
        int score = (int) Math.round(Math.max(0.0, Math.min(100.0, raw)));

        boolean requiredSatisfied = coverage.missingRequiredValues() == 0;
        Band band;
        if (!requiredSatisfied) {
            // Hard cap. A form that cannot be submitted is not "mostly ready".
            band = Band.LOW;
        } else if (score >= HIGH_THRESHOLD) {
            band = Band.HIGH;
        } else if (score >= MEDIUM_THRESHOLD) {
            band = Band.MEDIUM;
        } else {
            band = Band.LOW;
        }

        boolean ready = requiredSatisfied && band != Band.LOW;
        return new AutomationConfidence(score, band, ready, rationale(coverage, band, requiredSatisfied));
    }

    private static String rationale(SelectorCoverage coverage, Band band, boolean requiredSatisfied) {
        StringBuilder sb = new StringBuilder();
        if (!requiredSatisfied) {
            sb.append(coverage.missingRequiredValues())
                    .append(" required field(s) have no verified value — capped at LOW regardless of score. ");
        }
        sb.append(coverage.supportedControls()).append('/').append(coverage.fillableControls())
                .append(" drivable controls identified");
        if (coverage.unknownControls() > 0) {
            sb.append(", ").append(coverage.unknownControls()).append(" unidentified");
        }
        if (coverage.unsupportedControls() > 0) {
            sb.append(", ").append(coverage.unsupportedControls()).append(" unsupported control type(s)");
        }
        if (coverage.requiredControls() == 0) {
            // Said out loud rather than silently scoring 60/60: many ATSes enforce requiredness in
            // JavaScript, so "no required fields declared" is an absence of evidence, not evidence
            // that nothing is required.
            sb.append(". NOTE: the page declares no required fields, so required-field coverage is ")
                    .append("unproven rather than satisfied");
        }
        sb.append(". Band ").append(band).append('.');
        return sb.toString();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("band", band.name());
        out.put("ready", ready);
        out.put("rationale", rationale);
        return out;
    }
}
