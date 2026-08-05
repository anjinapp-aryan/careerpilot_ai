package ai.careerpilot.execution.browser.multistep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase F1 — decides whether it is safe to leave the current page.
 *
 * <p>Pure, stateless, deterministic and thread-safe — the same discipline as
 * {@code VerificationAdjudicator}, {@code AutomationConfidence} and {@code FormControlReducer}. It
 * performs no I/O: the caller gathers observations from the live page and this class only judges
 * them, which is what makes every rule below testable without launching a browser.
 *
 * <p><b>The guard is a veto, never a permission.</b> It cannot cause navigation; it can only stop
 * it. Every unknown resolves to "stop": a check whose input could not be gathered is treated as
 * failed, because on a real employer form the cost of stopping is a human looking at a screenshot,
 * and the cost of continuing wrongly is an incomplete application delivered under someone's real
 * name.
 */
public final class StepAdvanceGuard {

    private StepAdvanceGuard() {
    }

    /**
     * What the caller observed on the page after filling it.
     *
     * @param requiredUnresolved required controls with no verified value — a hard stop
     * @param validationErrors   messages surfaced by {@code ValidationErrorDetector}
     * @param expectedUploads    uploads the plan intended to make
     * @param verifiedUploads    uploads read back as genuinely present
     * @param captchaDetected    a CAPTCHA or login wall is on the page
     * @param urlAtFill          the URL when filling started
     * @param urlNow             the URL now
     * @param pageStable         the page reached a settled state
     * @param sessionAlive       no sign-in wall or session-expiry indication
     * @param attemptCount       how many times this step has been attempted
     */
    public record Observation(List<String> requiredUnresolved,
                              List<String> validationErrors,
                              int expectedUploads,
                              int verifiedUploads,
                              boolean captchaDetected,
                              String urlAtFill,
                              String urlNow,
                              boolean pageStable,
                              boolean sessionAlive,
                              int attemptCount) {

        public Observation {
            requiredUnresolved = requiredUnresolved == null ? List.of() : List.copyOf(requiredUnresolved);
            validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
        }
    }

    /** The verdict. {@code blockers} is empty exactly when {@link #safe()} is true. */
    public record Verdict(boolean safe, List<String> blockers) {

        public Verdict {
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
        }

        public Map<String, Object> snapshot() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("safeToAdvance", safe);
            out.put("blockers", blockers);
            return out;
        }
    }

    /**
     * Bounded attempts. A wizard that never settles must not be retried forever; after this many
     * tries the step is escalated to a human rather than re-driven.
     */
    public static final int MAX_ATTEMPTS = 3;

    /**
     * Evaluate. Never throws; a null observation is itself a blocker, because "we could not observe
     * the page" is not evidence that the page is fine.
     */
    public static Verdict evaluate(Observation o) {
        if (o == null) {
            return new Verdict(false, List.of("no observation available — cannot prove the page is safe to leave"));
        }
        List<String> blockers = new ArrayList<>();

        if (!o.requiredUnresolved().isEmpty()) {
            blockers.add(o.requiredUnresolved().size() + " required control(s) have no verified value: "
                    + String.join("; ", o.requiredUnresolved()));
        }
        if (!o.validationErrors().isEmpty()) {
            blockers.add("the page is reporting validation errors: "
                    + String.join("; ", o.validationErrors()));
        }
        if (o.verifiedUploads() < o.expectedUploads()) {
            // An upload that cannot be read back did not happen, whatever the widget claims.
            blockers.add("upload verification failed: expected " + o.expectedUploads()
                    + " file(s) present, verified " + o.verifiedUploads());
        }
        if (o.captchaDetected()) {
            // Detected and reported, never solved.
            blockers.add("a CAPTCHA or login wall is present — reported, never solved");
        }
        if (!o.sessionAlive()) {
            blockers.add("the session is no longer valid (sign-in or expiry detected)");
        }
        if (!o.pageStable()) {
            blockers.add("the page did not reach a stable state");
        }
        if (redirected(o)) {
            // The employer moved us. Continuing would fill a page nobody reviewed.
            blockers.add("unexpected navigation: the page moved from " + o.urlAtFill()
                    + " to " + o.urlNow() + " during this step");
        }
        if (o.attemptCount() > MAX_ATTEMPTS) {
            blockers.add("attempt limit reached (" + o.attemptCount() + " of " + MAX_ATTEMPTS
                    + ") — escalating to human review rather than retrying");
        }
        return new Verdict(blockers.isEmpty(), blockers);
    }

    /**
     * A redirect that matters. Compared on scheme+host+path only: employer forms routinely rewrite
     * query strings and fragments as a wizard progresses, and treating those as a redirect would
     * stop every legitimate multi-step form on its first transition.
     */
    static boolean redirected(Observation o) {
        String before = normalise(o.urlAtFill());
        String after = normalise(o.urlNow());
        if (before.isEmpty() || after.isEmpty()) return false;   // unknown, judged by other rules
        return !before.equals(after);
    }

    private static String normalise(String url) {
        if (url == null || url.isBlank()) return "";
        String trimmed = url.trim();
        int cut = trimmed.indexOf('#');
        if (cut >= 0) trimmed = trimmed.substring(0, cut);
        cut = trimmed.indexOf('?');
        if (cut >= 0) trimmed = trimmed.substring(0, cut);
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed.toLowerCase(java.util.Locale.ROOT);
    }
}
