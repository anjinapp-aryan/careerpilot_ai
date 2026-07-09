package ai.careerpilot.jobdiscovery.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 5F — Wellfound (AngelList Talent) source, registered so the provider framework and
 * diagnostics can report on it, but deliberately inert: Wellfound has no public jobs API, and
 * its listing pages are JS-rendered, so a compliant/reliable scrape needs a headless browser
 * plus explicit robots.txt handling — out of scope for a safe implementation in this pass. It
 * ships dark ({@link #isConfigured()} always false) rather than fabricate a crawler that cannot
 * be verified to behave correctly or respect crawl policy. Flip {@code career.discovery.wellfound.enabled}
 * only once a real, robots.txt-respecting crawler backs {@link #fetch()}.
 */
@Component
public class WellfoundProvider implements JobProvider {

    private final boolean enabled;

    public WellfoundProvider(@Value("${career.discovery.wellfound.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override public String name() { return "wellfound"; }

    /** Always false: no real crawler is implemented (see class javadoc). Flag exists for future wiring. */
    @Override public boolean isConfigured() { return false; }

    /** Whether the dark flag is on — surfaced by diagnostics even though {@link #isConfigured()} stays false. */
    public boolean isFlagEnabled() { return enabled; }

    @Override public List<RawJob> fetch() { return List.of(); }
}
