package ai.careerpilot.jobdiscovery.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 5.2 — DevITjobs UK adapter. Registered so the provider framework and diagnostics can
 * report on it, but deliberately inert: the previously-public {@code devitjobs.uk/job_feed.xml}
 * and {@code /rss} endpoints now 302-redirect to {@code devitjobs.jobcopilot.com/signup} — the
 * board has migrated to the JobCopilot platform and no longer exposes a public feed at its old
 * domain (verified by direct HTTP probe: both URLs resolve to a sign-up landing page, not job
 * data). Per the "do not fabricate endpoints" constraint, this ships dark ({@link #isConfigured()}
 * always false) rather than invent a scraper against a page that isn't a real data feed. Flip
 * {@code career.discovery.devitjobs.enabled} only once a real, documented public endpoint is
 * confirmed for this source — same precedent as {@link WellfoundProvider}.
 */
@Component
public class DevItJobsProvider implements JobProvider {

    private final boolean enabled;

    public DevItJobsProvider(@Value("${career.discovery.devitjobs.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override public String name() { return "devitjobs"; }

    /** Always false: no live public feed exists at this source (see class javadoc). */
    @Override public boolean isConfigured() { return false; }

    /** Whether the dark flag is on — surfaced by diagnostics even though {@link #isConfigured()} stays false. */
    public boolean isFlagEnabled() { return enabled; }

    @Override public List<RawJob> fetch() { return List.of(); }
}
