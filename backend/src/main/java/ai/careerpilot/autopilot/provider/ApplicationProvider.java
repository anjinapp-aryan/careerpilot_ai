package ai.careerpilot.autopilot.provider;

/**
 * Phase 7.4 — adapter contract for submitting an application to one external ATS. Mirrors the
 * {@code JobProvider} convention (a provider joins the registry as a Spring bean; the registry
 * picks one by {@link #supports}). The browser/HTTP submission layer lives <em>behind</em> this
 * seam — no provider-specific or browser logic ever leaks into the autopilot business services.
 *
 * <p><b>Safety invariant:</b> {@link #submit} must never return {@code SUBMITTED} unless the
 * application was genuinely delivered. Providers without a real, credentialed integration return
 * {@code HUMAN_REVIEW} — the agent never fabricates a successful application.
 */
public interface ApplicationProvider {

    /** Stable lowercase provider name (e.g. "greenhouse"), persisted with each submission. */
    String name();

    /** Whether this provider recognizes the given application URL (host match). */
    boolean supports(String applicationUrl);

    /**
     * Whether a real automated-submission integration is wired for this provider in the current
     * configuration. False for every provider today (no credentialed API / browser layer exists),
     * so the auto-apply engine routes everything to human review.
     */
    boolean autoSubmitConfigured();

    /** Attempt submission. Never fabricates success — returns {@code HUMAN_REVIEW} when it cannot truly submit. */
    SubmissionResult submit(ApplicationSubmissionRequest request);

    /** A fallback provider is chosen only when no specific provider matches the URL (lowest priority). */
    default boolean isFallback() {
        return false;
    }
}
