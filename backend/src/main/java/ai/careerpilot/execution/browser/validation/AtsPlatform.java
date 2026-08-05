package ai.careerpilot.execution.browser.validation;

import java.util.List;
import java.util.Locale;

/**
 * Phase 12C.5 — which ATS a validated page appears to belong to.
 *
 * <p><b>Reporting only. Nothing in the automation path may branch on this value.</b> The whole
 * design of {@code execution/browser/form/} is that a form it has never seen is handled exactly
 * like one it has; a vendor enum that selectors keyed off would undo that in a single commit. This
 * exists so a compatibility report can say "Greenhouse: 41 selectors supported, 1 unsupported"
 * instead of listing anonymous URLs — an operator needs to know <em>which</em> ATS is weak.
 *
 * <p>Detection is a host-token match, the same shape as {@code AbstractStubConnector#hostToken()}
 * already uses for connector routing. An unrecognised host is {@link #UNKNOWN}, never a guess — an
 * ATS we cannot name is precisely one whose compatibility numbers should not be filed under a
 * vendor that might be innocent of them.
 */
public enum AtsPlatform {

    GREENHOUSE("greenhouse.io", "boards.greenhouse.io", "job-boards.greenhouse.io"),
    LEVER("lever.co", "jobs.lever.co"),
    ASHBY("ashbyhq.com", "jobs.ashbyhq.com"),
    SMARTRECRUITERS("smartrecruiters.com"),
    TEAMTAILOR("teamtailor.com"),
    RECRUITEE("recruitee.com"),
    WORKDAY("myworkdayjobs.com", "workday.com"),
    WORKABLE("workable.com"),
    PERSONIO("personio.de", "jobs.personio.de"),
    BAMBOOHR("bamboohr.com"),
    /** A real page on a host we do not recognise — typically a company's own careers portal. */
    UNKNOWN();

    private final List<String> hostTokens;

    AtsPlatform(String... hostTokens) {
        this.hostTokens = List.of(hostTokens);
    }

    public List<String> hostTokens() {
        return hostTokens;
    }

    /**
     * Identify the platform from a URL. Matches on the host only — a path or query string can
     * contain any string at all, and matching there would let an arbitrary careers page be filed
     * under someone else's vendor by coincidence.
     */
    public static AtsPlatform detect(String url) {
        String host = hostOf(url);
        if (host.isEmpty()) return UNKNOWN;
        for (AtsPlatform platform : values()) {
            for (String token : platform.hostTokens) {
                if (host.equals(token) || host.endsWith("." + token)) return platform;
            }
        }
        return UNKNOWN;
    }

    /** Lowercased host, or empty when the URL is unparseable. Never throws. */
    static String hostOf(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }
}
