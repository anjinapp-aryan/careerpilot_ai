package ai.careerpilot.autopilot.provider;

import java.util.List;
import java.util.Locale;

/**
 * Phase 7.4 — shared host-matching + fail-safe {@code submit} for the ATS providers. Each concrete
 * provider only declares its {@link #name()} and {@link #hostPatterns()}; recognizing a URL and the
 * safety behavior are inherited.
 *
 * <p>{@link #autoSubmitConfigured()} is {@code false} and {@link #submit} returns {@code HUMAN_REVIEW}
 * because no credentialed submission integration or browser layer exists anywhere in the codebase.
 * When a real integration is added for a provider, it overrides {@code autoSubmitConfigured()} and
 * {@code submit()} — nothing else in the autopilot changes.
 */
public abstract class AbstractAtsProvider implements ApplicationProvider {

    /** Lowercase host fragments that identify this ATS in an application URL. */
    protected abstract List<String> hostPatterns();

    @Override
    public boolean supports(String applicationUrl) {
        if (applicationUrl == null || applicationUrl.isBlank()) return false;
        String u = applicationUrl.toLowerCase(Locale.ROOT);
        return hostPatterns().stream().anyMatch(u::contains);
    }

    @Override
    public boolean autoSubmitConfigured() {
        return false;
    }

    @Override
    public SubmissionResult submit(ApplicationSubmissionRequest request) {
        return SubmissionResult.humanReview(name(),
                "Automated submission for " + name() + " is not integrated — routed to human review.");
    }
}
