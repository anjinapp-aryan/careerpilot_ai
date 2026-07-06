package ai.careerpilot.jobdiscovery.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 5G — direct-crawl source for named company career sites (Google, Microsoft, Amazon,
 * JPMorgan, Oracle, Salesforce, Adobe, Atlassian, SAP, IBM). All ten run custom, JS-rendered
 * ATS front-ends with no stable public JSON endpoint, and several publish per-site robots.txt /
 * ToS crawl restrictions that would need individual review before automated fetching is safe.
 * Building ten bespoke, verified-correct HTML crawlers is out of scope for this pass; this
 * provider is registered (so the framework/diagnostics/flags exist end-to-end) but ships
 * permanently inert until a real, per-site, robots.txt-respecting {@code CompanyCareerCrawler}
 * backs {@link #fetch()} for at least one site.
 */
@Component
public class CompanyCareerSiteProvider implements JobProvider {

    /** Registry of company names Phase 5G targets — not yet backed by any real crawl logic. */
    public static final List<String> TARGET_COMPANIES = List.of(
            "Google", "Microsoft", "Amazon", "JPMorgan", "Oracle",
            "Salesforce", "Adobe", "Atlassian", "SAP", "IBM");

    private final boolean enabled;

    public CompanyCareerSiteProvider(@Value("${career.discovery.company.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override public String name() { return "company-career-sites"; }

    /** Always false: no real crawler is implemented (see class javadoc). Flag exists for future wiring. */
    @Override public boolean isConfigured() { return false; }

    /** Whether the dark flag is on — surfaced by diagnostics even though {@link #isConfigured()} stays false. */
    public boolean isFlagEnabled() { return enabled; }

    @Override public List<RawJob> fetch() { return List.of(); }
}
