package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.ats.AbstractStubConnector;
import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import ai.careerpilot.execution.verification.VerificationResult;
import ai.careerpilot.execution.verification.evidence.ConfirmationPageVerifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Gap D — Greenhouse ATS connector. Greenhouse job postings commonly embed a well-documented,
 * public, no-login "Apply" form directly on the posting page — a genuine guest-apply flow, unlike
 * LinkedIn Easy Apply (requires an authenticated LinkedIn account, left stubbed) — so this
 * connector is one of exactly two ({@code lever} is the other) allowed onto the real browser
 * automation path (see {@link ai.careerpilot.execution.ats.GuestApplyEligibility}, the hardcoded
 * enforcement point).
 *
 * <p>{@link #extractForm} returns a SCHEMA only (CSS selector -> field type), not values — actual
 * filling with real, non-fabricated applicant data happens in {@code GuestApplyAutomationService}.
 * {@code authenticate}/{@code track} are still inherited stubs: no login is ever performed, and
 * Greenhouse exposes no public no-login status-tracking API.
 */
@Component
public class GreenhouseConnector extends AbstractStubConnector {

    private final PlaywrightAutomationProvider browser;
    private final ConfirmationPageVerifier verifier;

    public GreenhouseConnector(PlaywrightAutomationProvider browser, ConfirmationPageVerifier verifier) {
        this.browser = browser;
        this.verifier = verifier;
    }

    @Override public String name() { return "greenhouse"; }
    @Override protected String hostToken() { return "greenhouse.io"; }

    /** Real only when the browser-automation engine itself is configured (flag on + Playwright ready). */
    @Override
    public boolean isConfigured() {
        return browser.isConfigured();
    }

    @Override
    public Map<String, String> extractForm(Job job) {
        // Greenhouse's standard embedded application form field ids — documented, stable pattern
        // across Greenhouse job boards. Selector -> recognized field type (see
        // GuestApplyAutomationService#resolveFields).
        return Map.of(
                "#first_name", "first_name",
                "#last_name", "last_name",
                "#email", "email");
    }

    /**
     * {@code browser.submit()} itself always returns {@code null} (it only clicks the button) —
     * so without this, {@code confirmationReference} would always be null even on a real
     * submission and verification could never produce anything but UNABLE_TO_VERIFY. Captures the
     * post-submit page content as the evidence signal instead. This is honest, not a fabricated
     * ATS confirmation number: it proves *a page rendered after the click*, nothing more — see
     * {@link #verifySubmission} for exactly what it does and does not certify.
     */
    @Override
    public String submit(Job job, Map<String, String> answers) {
        browser.submit();
        try {
            String content = browser.captureConfirmation();
            return content == null ? null : truncate(content, 2000);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Phase 0 (Browser Automation Platform) — real evidence adjudication over the captured
     * post-submit page: confirmation phrases, an extractable application reference, and failure
     * indicators, weighed by {@code VerificationAdjudicator}.
     *
     * <p>Replaces the previous rule, which returned VERIFIED whenever the captured page exceeded
     * 50 characters — a rendered error page satisfied that check, so the platform could certify a
     * submission that had failed.
     */
    @Override
    public VerificationResult verifySubmission(String confirmationReference) {
        return verifier.verify(confirmationReference);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
