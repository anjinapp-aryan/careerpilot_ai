package ai.careerpilot.jobdiscovery.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Phase 5.3 — Enterprise ATS Connector Framework, Oracle Taleo adapter: connector architecture
 * only, deliberately inert. Unlike Workday, Taleo has no single, verified, keyless public JSON
 * endpoint that works uniformly across tenants: Oracle's documented REST API
 * ({@code {host}/object/...}) requires per-instance credentials, and the only unauthenticated
 * public surface is a per-customer sitemap
 * ({@code https://{customer}.taleo.net/careersection/sitemap.jss}) — an XML sitemap of career
 * page URLs, not a structured job-data feed, and its shape is not consistent/documented enough
 * to map safely without per-tenant verification. Per the "do not fabricate endpoints/scraping"
 * constraint, this ships as registered-but-inert ({@link #isConfigured()} always {@code false}),
 * same precedent as {@link WellfoundProvider}/{@link CompanyCareerSiteProvider} — but the
 * company-connector config model is real and parseable now, so onboarding a verified Taleo
 * source later is a config change, not a redesign.
 */
@Component
public class TaleoProvider implements JobProvider {

    private final boolean enabled;
    private final boolean enterpriseEnabled;
    private final List<EnterpriseAtsCompanyConnector> companies;

    public TaleoProvider(
            @Value("${career.discovery.taleo.enabled:false}") boolean enabled,
            @Value("${career.discovery.enterprise.enabled:false}") boolean enterpriseEnabled,
            @Value("${career.discovery.taleo.companies:}") String companiesCsv) {
        this.enabled = enabled;
        this.enterpriseEnabled = enterpriseEnabled;
        this.companies = EnterpriseAtsCompanyConnector.parseAll(companiesCsv);
    }

    @Override public String name() { return "taleo"; }

    /** Always false: no verified universal public data feed exists (see class javadoc). */
    @Override public boolean isConfigured() { return false; }

    /** Whether both the framework master switch and this provider's own flag are on — diagnostics-only, {@link #isConfigured()} stays false regardless. */
    public boolean isFlagEnabled() { return enterpriseEnabled && enabled; }

    /** Exposed for diagnostics: configured connectors, parsed but never fetched. */
    public List<EnterpriseAtsCompanyConnector> companies() { return Collections.unmodifiableList(companies); }

    @Override public List<RawJob> fetch() { return List.of(); }
}
