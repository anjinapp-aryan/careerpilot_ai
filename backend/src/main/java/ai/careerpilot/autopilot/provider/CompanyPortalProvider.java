package ai.careerpilot.autopilot.provider;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 7.4 — the fallback provider for any application URL that no specific ATS provider claims
 * (a direct company career portal). Chosen last, and only when a URL is present. Submission is never
 * automated for an unknown portal, so it always routes to human review.
 */
@Component
public class CompanyPortalProvider extends AbstractAtsProvider {
    @Override public String name() { return "company-portal"; }
    @Override protected List<String> hostPatterns() { return List.of(); }
    @Override public boolean isFallback() { return true; }

    /** As the fallback, it claims any non-blank URL the specific providers didn't. */
    @Override public boolean supports(String applicationUrl) {
        return applicationUrl != null && !applicationUrl.isBlank();
    }
}
