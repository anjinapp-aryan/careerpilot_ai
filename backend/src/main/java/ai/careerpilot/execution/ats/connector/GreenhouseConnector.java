package ai.careerpilot.execution.ats.connector;

import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.ats.AbstractStubConnector;
import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
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

    public GreenhouseConnector(PlaywrightAutomationProvider browser) {
        this.browser = browser;
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

    @Override
    public String submit(Job job, Map<String, String> answers) {
        return browser.submit();
    }
}
