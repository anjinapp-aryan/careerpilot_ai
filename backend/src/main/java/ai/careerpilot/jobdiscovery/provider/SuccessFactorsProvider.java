package ai.careerpilot.jobdiscovery.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Phase 5.3 — Enterprise ATS Connector Framework, SAP SuccessFactors adapter: connector
 * architecture only, deliberately inert. SuccessFactors' documented public interface is the
 * OData v2 {@code JobRequisition} entity, which requires per-tenant OAuth2/SAML credentials —
 * there is no keyless universal public endpoint. An undocumented {@code sitemap.xml} career-site
 * convention is referenced in third-party scraping write-ups, but it is unofficial, tenant
 * inconsistent, and unverified against a real SuccessFactors career site during this
 * implementation, so mapping it here would risk exactly the "fabricate endpoints" this phase
 * forbids. Ships registered-but-inert ({@link #isConfigured()} always {@code false}), same
 * precedent as {@link WellfoundProvider}/{@link TaleoProvider} — the company-connector config
 * model is real now, so onboarding a verified source later is a config change, not a redesign.
 */
@Component
public class SuccessFactorsProvider implements JobProvider {

    private final boolean enabled;
    private final boolean enterpriseEnabled;
    private final List<EnterpriseAtsCompanyConnector> companies;

    public SuccessFactorsProvider(
            @Value("${career.discovery.successfactors.enabled:false}") boolean enabled,
            @Value("${career.discovery.enterprise.enabled:false}") boolean enterpriseEnabled,
            @Value("${career.discovery.successfactors.companies:}") String companiesCsv) {
        this.enabled = enabled;
        this.enterpriseEnabled = enterpriseEnabled;
        this.companies = EnterpriseAtsCompanyConnector.parseAll(companiesCsv);
    }

    @Override public String name() { return "successfactors"; }

    /** Always false: no verified keyless public data feed exists (see class javadoc). */
    @Override public boolean isConfigured() { return false; }

    /** Whether both the framework master switch and this provider's own flag are on — diagnostics-only, {@link #isConfigured()} stays false regardless. */
    public boolean isFlagEnabled() { return enterpriseEnabled && enabled; }

    /** Exposed for diagnostics: configured connectors, parsed but never fetched. */
    public List<EnterpriseAtsCompanyConnector> companies() { return Collections.unmodifiableList(companies); }

    @Override public List<RawJob> fetch() { return List.of(); }
}
