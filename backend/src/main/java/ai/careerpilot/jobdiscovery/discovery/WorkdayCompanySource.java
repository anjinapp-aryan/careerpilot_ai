package ai.careerpilot.jobdiscovery.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Gap A — Company Discovery Agent. Probes a Workday tenant's real, keyless, public CXS search
 * endpoint ({@code https://{tenant}.{cluster}.myworkdayjobs.com/wday/cxs/{tenant}/{site}/jobs},
 * same verified endpoint shape {@link ai.careerpilot.jobdiscovery.provider.WorkdayProvider}
 * already fetches from). Unlike Greenhouse/Ashby/SmartRecruiters (one flat slug), a Workday
 * tenant needs three coordinates — the candidate slug is therefore expected in the same
 * semicolon-delimited shape as {@code career.discovery.workday.companies} config lines:
 * {@code tenant;cluster;site} (only the first three fields are read; extra fields are ignored).
 * A malformed candidate (missing tenant/cluster/site) is a miss with no HTTP call made.
 */
@Component
public class WorkdayCompanySource extends AbstractWebCompanySource implements CompanySource {

    private static final Logger log = LoggerFactory.getLogger(WorkdayCompanySource.class);

    private final boolean enabled;

    public WorkdayCompanySource(
            @Value("${jobs.discovery.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent,
            @Value("${company.discovery.enabled:false}") boolean enabled) {
        // No fixed base URL: every tenant has its own host, so each call passes an absolute URI —
        // same reasoning as WorkdayProvider's constructor.
        super("", userAgent);
        this.enabled = enabled;
    }

    @Override public String name() { return "workday"; }

    @Override public boolean isConfigured() { return enabled; }

    @Override
    public Optional<DiscoveredCandidate> probe(String candidateSlug) {
        String[] parts = parseTriple(candidateSlug);
        if (parts == null) return Optional.empty();
        String tenant = parts[0], cluster = parts[1], site = parts[2];
        String jobsUrl = "https://" + tenant + "." + cluster + ".myworkdayjobs.com/wday/cxs/" + tenant + "/" + site + "/jobs";
        try {
            Map<String, Object> body = client.post()
                    .uri(jobsUrl)
                    .bodyValue(Map.of("limit", 1, "offset", 0))
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return mapResponse(body, tenant, cluster, site);
        } catch (Exception e) {
            log.debug("workday company-probe miss for '{}': {}", candidateSlug, e.toString());
            return Optional.empty();
        }
    }

    /** Package-private for direct unit testing without standing up a mock HTTP server. */
    Optional<DiscoveredCandidate> mapResponse(Map<String, Object> body, String tenant, String cluster, String site) {
        if (body == null || !(body.get("jobPostings") instanceof java.util.List<?>)) return Optional.empty();
        String careerUrl = "https://" + tenant + "." + cluster + ".myworkdayjobs.com/" + site;
        return Optional.of(new DiscoveredCandidate("WORKDAY", tenant, careerUrl, tenant, cluster, site));
    }

    /** Package-private for direct unit testing. Returns null for a malformed/incomplete entry. */
    static String[] parseTriple(String candidateSlug) {
        if (candidateSlug == null || candidateSlug.isBlank()) return null;
        String[] f = candidateSlug.split(";", -1);
        if (f.length < 3) return null;
        String tenant = f[0].trim(), cluster = f[1].trim(), site = f[2].trim();
        if (tenant.isEmpty() || cluster.isEmpty() || site.isEmpty()) return null;
        return new String[] {tenant, cluster, site};
    }
}
