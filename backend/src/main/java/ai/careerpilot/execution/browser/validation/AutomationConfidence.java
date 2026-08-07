package ai.careerpilot.execution.browser.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
public record AutomationConfidence(int score, Band band, boolean ready, String rationale,
                                   List<AutomationBlocker> blockers) {

    /**
     * P0 compatibility constructor — a confidence with no blockers. Kept so every pre-P0 caller and
     * fixture compiles unchanged; the blocker-aware factory is {@link #from(SelectorCoverage, List)}.
     */
    public AutomationConfidence(int score, Band band, boolean ready, String rationale) {
        this(score, band, ready, rationale, List.of());
    }

    public AutomationConfidence {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        // The invariant, enforced in the constructor rather than trusted at every call site: a page
        // with a blocker is never ready, no matter what score was computed or what a caller passed.
        if (!blockers.isEmpty()) {
            ready = false;
        }
    }

    /** True when execution is impossible regardless of how well the page was analysed. */
    public boolean blocked() {
        return !blockers.isEmpty();
    }

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
        return from(coverage, List.of());
    }

    /**
     * P0 — score the analysis, then let blockers decide whether execution is possible.
     *
     * <p>The two are computed independently on purpose. {@code score} keeps meaning exactly what it
     * always did — how completely the form was understood — and remains useful on a blocked page
     * (an operator still wants to know the selectors are right before deciding a CAPTCHA is the
     * only obstacle). {@code ready} is the execution verdict, and a single blocker makes it false
     * regardless of score. Nothing is averaged, so a high coverage score can never dilute a hard
     * block.
     */
    public static AutomationConfidence from(SelectorCoverage coverage, List<AutomationBlocker> blockers) {
        List<AutomationBlocker> blocking = blockers == null ? List.of() : List.copyOf(blockers);
        if (coverage == null || coverage.totalControls() == 0) {
            List<AutomationBlocker> withNoForm = new ArrayList<>(blocking);
            if (withNoForm.stream().noneMatch(b -> b.reason() == AutomationBlocker.Reason.NO_FORM)) {
                withNoForm.add(AutomationBlocker.of(AutomationBlocker.Reason.NO_FORM));
            }
            return new AutomationConfidence(0, Band.LOW, false,
                    "no controls discovered — nothing to score", withNoForm);
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

        // The compact constructor forces this to false when a blocker exists; stating it here too
        // keeps the intent readable at the point the verdict is formed.
        boolean ready = requiredSatisfied && band != Band.LOW && blocking.isEmpty();
        return new AutomationConfidence(score, band, ready,
                rationale(coverage, band, requiredSatisfied, blocking), blocking);
    }

    private static String rationale(SelectorCoverage coverage, Band band, boolean requiredSatisfied,
                                    List<AutomationBlocker> blockers) {
        StringBuilder sb = new StringBuilder();
        // Stated first, because it is the only sentence that changes what an operator may do next.
        if (!blockers.isEmpty()) {
            sb.append("AUTOMATION BLOCKED — ");
            for (int i = 0; i < blockers.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(blockers.get(i).reason().name()).append(": ")
                        .append(blockers.get(i).detail());
            }
            sb.append(". The score below describes how well the page was ANALYSED, not whether it "
                    + "can be executed — execution is not possible. ");
        }
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
        // P0 — emitted before the rationale so a client reading top-down learns execution is
        // impossible before it reads a score it might otherwise act on.
        out.put("blocked", blocked());
        out.put("blockers", blockers.stream().map(AutomationBlocker::snapshot).toList());
        out.put("blockedReason", blockers.isEmpty() ? null : blockers.get(0).reason().name());
        out.put("rationale", rationale);
        return out;
    }
}
