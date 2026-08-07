package ai.careerpilot.execution.browser.validation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P0 — a reason automation <b>cannot execute</b> on a page, however well that page was analysed.
 *
 * <h2>The bug this exists to make impossible</h2>
 * Measured on live GitLab postings during the F5 audit:
 * <pre>
 *   captchaDetected = true    confidence = 94    band = HIGH    ready = true
 * </pre>
 * Three separate live postings reported that. It is not a tuning problem — {@code
 * AutomationConfidence} and {@code SelectorCoverage} contained no reference to CAPTCHA at all, so a
 * page guarded by reCAPTCHA scored on field coverage alone and was certified executable. Automation
 * would then drive that form and be stopped by a control the platform will never solve.
 *
 * <h2>Why a separate type rather than another term in the score</h2>
 * Because they answer different questions and must not be averaged. Field coverage measures <b>how
 * well the page was analysed</b>; a blocker states <b>whether execution is possible at all</b>. A
 * page can be perfectly analysed and completely unexecutable — that is exactly the CAPTCHA case —
 * and any formula that lets a high coverage score offset a hard block will reproduce this defect in
 * a new dimension. Blockers are therefore absolute: one is enough to make {@code ready} false, and
 * no score can override it.
 */
public record AutomationBlocker(Reason reason, String detail) {

    public enum Reason {
        /**
         * A CAPTCHA or bot-challenge provider is present. Detected, never solved — solving one is
         * circumventing an access control the employer deliberately placed.
         */
        CAPTCHA("a CAPTCHA challenge is present and is never solved by this platform"),

        /** A sign-in wall stands between the browser and the form. */
        LOGIN_WALL("the page requires authentication, which this platform never performs"),

        /** Required fields exist that no verified candidate data can fill. */
        MISSING_REQUIRED_DATA("required fields have no verified value and are never fabricated"),

        /** Nothing drivable was found — there is no form to execute against. */
        NO_FORM("no drivable form controls were discovered"),

        /**
         * Controls were found only inside cross-origin frames. Not a discovery bug: reading across
         * that boundary is prevented by the browser, and the platform does not attempt it.
         */
        CROSS_ORIGIN_FRAME("the form appears to be inside a cross-origin frame, which cannot be driven");

        private final String description;

        Reason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public static AutomationBlocker of(Reason reason) {
        return new AutomationBlocker(reason, reason.description());
    }

    public static AutomationBlocker of(Reason reason, String detail) {
        return new AutomationBlocker(reason, detail == null || detail.isBlank()
                ? reason.description() : detail);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reason", reason.name());
        out.put("detail", detail);
        return out;
    }
}
