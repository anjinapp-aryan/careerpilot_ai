package ai.careerpilot.execution.ats;

import java.util.Locale;
import java.util.Set;

/**
 * Gap D — the single, hardcoded source of truth for which ATS connectors are allowed onto the real
 * guest/no-login browser-automation path. This is intentionally NOT a configuration flag: per the
 * product safety decision, {@code browser.automation.guest-apply-only} is a diagnostics-visibility
 * flag only (see {@code PlaywrightAutomationProvider#flagEnabled()} / {@code
 * ai.careerpilot.jobdiscovery.provider.WellfoundProvider#isFlagEnabled()} for the same
 * visible-but-not-controlling precedent in this codebase) — the actual enforcement lives here, as a
 * compile-time constant, so no runtime configuration change can add a login-required connector
 * (e.g. LinkedIn Easy Apply) onto this path.
 *
 * <p>Greenhouse and Lever both have well-documented, public, no-login "Apply" forms embedded
 * directly on their job posting pages. LinkedIn Easy Apply requires an authenticated LinkedIn
 * account and therefore never qualifies. Workday, BambooHR, Ashby, and SmartRecruiters postings
 * commonly require account creation or gate parts of the flow behind auth, so they are excluded
 * here too — left exactly as inert stubs (see {@code AbstractStubConnector}).
 */
public final class GuestApplyEligibility {

    /** Hardcoded allowlist — do not make this configurable. */
    private static final Set<String> GUEST_APPLY_ELIGIBLE = Set.of("greenhouse", "lever");

    private GuestApplyEligibility() {}

    public static boolean isEligible(String connectorName) {
        if (connectorName == null || connectorName.isBlank()) return false;
        return GUEST_APPLY_ELIGIBLE.contains(connectorName.toLowerCase(Locale.ROOT));
    }
}
