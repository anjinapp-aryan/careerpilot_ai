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
 * Gap A — Company Discovery Agent. Probes Ashby's real, keyless, public Job Board API
 * ({@code https://api.ashbyhq.com/posting-api/job-board/{orgSlug}}, same verified endpoint
 * {@link ai.careerpilot.jobdiscovery.provider.AshbyProvider} already fetches from) for a
 * candidate org slug. A well-formed response with a {@code jobs} array counts as a hit
 * (empty-but-valid board still counts, same reasoning as {@link GreenhouseCompanySource}).
 */
@Component
public class AshbyCompanySource extends AbstractWebCompanySource implements CompanySource {

    private static final Logger log = LoggerFactory.getLogger(AshbyCompanySource.class);

    private final boolean enabled;

    public AshbyCompanySource(
            @Value("${career.discovery.company-agent.ashby.base-url:https://api.ashbyhq.com}") String baseUrl,
            @Value("${jobs.discovery.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent,
            @Value("${company.discovery.enabled:false}") boolean enabled) {
        super(baseUrl, userAgent);
        this.enabled = enabled;
    }

    @Override public String name() { return "ashby"; }

    @Override public boolean isConfigured() { return enabled; }

    @Override
    public Optional<DiscoveredCandidate> probe(String candidateSlug) {
        if (candidateSlug == null || candidateSlug.isBlank()) return Optional.empty();
        try {
            Map<String, Object> body = client.get()
                    .uri("/posting-api/job-board/{org}", candidateSlug)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return mapResponse(body, candidateSlug);
        } catch (Exception e) {
            log.debug("ashby company-probe miss for '{}': {}", candidateSlug, e.toString());
            return Optional.empty();
        }
    }

    /** Package-private for direct unit testing without standing up a mock HTTP server. */
    Optional<DiscoveredCandidate> mapResponse(Map<String, Object> body, String slug) {
        if (body == null || !(body.get("jobs") instanceof java.util.List<?>)) return Optional.empty();
        return Optional.of(new DiscoveredCandidate(
                "ASHBY", slug, "https://jobs.ashbyhq.com/" + slug, null, null, null));
    }
}
