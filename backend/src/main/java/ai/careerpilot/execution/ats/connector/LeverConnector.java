package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.ats.AbstractStubConnector;
import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
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

    public LeverConnector(PlaywrightAutomationProvider browser) {
        this.browser = browser;
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

    @Override
    public String submit(Job job, Map<String, String> answers) {
        return browser.submit();
    }
}
