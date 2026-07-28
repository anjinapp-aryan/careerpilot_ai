package ai.careerpilot.jobdiscovery.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * International Job Discovery Engine, Phase 1 — architecture-ready, permanently inert. LinkedIn
 * Jobs has no free, ToS-compliant public search API: the official Jobs API requires LinkedIn
 * partner approval (not available to this project), and scraping linkedin.com violates LinkedIn's
 * Terms of Service. Per the "do not fabricate endpoints/scraping" constraint already applied to
 * {@link TaleoProvider}/{@link SuccessFactorsProvider}/{@link WellfoundProvider}, this ships as
 * registered-but-inert ({@link #isConfigured()} always {@code false}) so the {@link JobProvider}
 * strategy-pattern slot is real and future-proof (a licensed job-board aggregator API could plug
 * in here later) without ever scraping anything today.
 */
@Component
public class LinkedInProvider implements JobProvider {

    private final boolean enabled;

    public LinkedInProvider(@Value("${career.discovery.linkedin.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override public String name() { return "linkedin"; }

    /** Always false: no free/ToS-compliant public data feed exists (see class javadoc). */
    @Override public boolean isConfigured() { return false; }

    /** Diagnostics-only: whether the operator has flipped this provider's own flag on. {@link #isConfigured()} stays false regardless. */
    public boolean isFlagEnabled() { return enabled; }

    @Override public List<RawJob> fetch() { return List.of(); }
}
