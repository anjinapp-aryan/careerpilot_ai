package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.ats.AbstractStubConnector;
import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import ai.careerpilot.execution.verification.VerificationResult;
import ai.careerpilot.execution.verification.evidence.ConfirmationPageVerifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Gap D — Lever ATS connector. Lever job postings expose a well-documented, public, no-login
 * "Apply" form directly on the posting page — a genuine guest-apply flow — so, like Greenhouse,
 * this connector is allowed onto the real browser automation path (see {@link
 * ai.careerpilot.execution.ats.GuestApplyEligibility}, the hardcoded enforcement point).
 *
 * <p>{@link #extractForm} returns a SCHEMA only (CSS selector -> field type); real filling happens
 * in {@code GuestApplyAutomationService}. {@code authenticate}/{@code track} remain inherited
 * stubs — no login is ever performed.
 */
@Component
public class LeverConnector extends AbstractStubConnector {

    private final PlaywrightAutomationProvider browser;
    private final ConfirmationPageVerifier verifier;

    public LeverConnector(PlaywrightAutomationProvider browser, ConfirmationPageVerifier verifier) {
        this.browser = browser;
        this.verifier = verifier;
    }

    @Override public String name() { return "lever"; }
    @Override protected String hostToken() { return "lever.co"; }

    @Override
    public boolean isConfigured() {
        return browser.isConfigured();
    }

    @Override
    public Map<String, String> extractForm(Job job) {
        // Lever's standard posting-form field names (input[name="..."]) — documented, stable
        // pattern across Lever job postings.
        return Map.of(
                "input[name=\"name\"]", "name",
                "input[name=\"email\"]", "email");
    }

    /** Same evidence-capture fix as {@code GreenhouseConnector} — see its javadoc for why this is needed. */
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

    /** Phase 0 — same real evidence adjudication as {@code GreenhouseConnector.verifySubmission}; see its javadoc. */
    @Override
    public VerificationResult verifySubmission(String confirmationReference) {
        return verifier.verify(confirmationReference);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
