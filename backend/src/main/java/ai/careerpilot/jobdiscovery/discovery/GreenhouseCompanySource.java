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
 * Gap A — Company Discovery Agent. Probes Greenhouse's real, keyless, public Job Board API
 * ({@code https://boards-api.greenhouse.io/v1/boards/{token}/jobs}, same verified endpoint
 * {@link ai.careerpilot.jobdiscovery.provider.GreenhouseProvider} already fetches from) for a
 * candidate board token. A well-formed response with a {@code jobs} array (even if currently
 * empty — an empty-but-valid board is still a genuine board, not a false positive) counts as a
 * hit; a 404/error/malformed body is a miss. Single attempt, no retry loop.
 */
@Component
public class GreenhouseCompanySource extends AbstractWebCompanySource implements CompanySource {

    private static final Logger log = LoggerFactory.getLogger(GreenhouseCompanySource.class);

    private final boolean enabled;

    public GreenhouseCompanySource(
            @Value("${career.discovery.company-agent.greenhouse.base-url:https://boards-api.greenhouse.io/v1}") String baseUrl,
            @Value("${jobs.discovery.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent,
            @Value("${company.discovery.enabled:false}") boolean enabled) {
        super(baseUrl, userAgent);
        this.enabled = enabled;
    }

    @Override public String name() { return "greenhouse"; }

    @Override public boolean isConfigured() { return enabled; }

    @Override
    public Optional<DiscoveredCandidate> probe(String candidateSlug) {
        if (candidateSlug == null || candidateSlug.isBlank()) return Optional.empty();
        try {
            Map<String, Object> body = client.get()
                    .uri("/boards/{board}/jobs", candidateSlug)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return mapResponse(body, candidateSlug);
        } catch (Exception e) {
            log.debug("greenhouse company-probe miss for '{}': {}", candidateSlug, e.toString());
            return Optional.empty();
        }
    }

    /** Package-private for direct unit testing without standing up a mock HTTP server. */
    Optional<DiscoveredCandidate> mapResponse(Map<String, Object> body, String slug) {
        if (body == null || !(body.get("jobs") instanceof java.util.List<?>)) return Optional.empty();
        return Optional.of(new DiscoveredCandidate(
                "GREENHOUSE", slug, "https://boards.greenhouse.io/" + slug, null, null, null));
    }
}
