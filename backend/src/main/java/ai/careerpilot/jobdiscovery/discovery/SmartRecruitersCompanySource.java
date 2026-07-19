package ai.careerpilot.jobdiscovery.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gap A — Company Discovery Agent. Probes SmartRecruiters' real, keyless, public Posting API
 * ({@code https://api.smartrecruiters.com/v1/companies/{companyId}/postings}, same verified
 * endpoint {@link ai.careerpilot.jobdiscovery.provider.SmartRecruitersProvider} already fetches
 * from) for a candidate company id. A well-formed response with a {@code content} array counts
 * as a hit. When the array is non-empty, the display company name is read from the first
 * posting's {@code company.name} (more accurate than the slug); otherwise the slug itself is used.
 */
@Component
public class SmartRecruitersCompanySource extends AbstractWebCompanySource implements CompanySource {

    private static final Logger log = LoggerFactory.getLogger(SmartRecruitersCompanySource.class);

    private final boolean enabled;

    public SmartRecruitersCompanySource(
            @Value("${career.discovery.company-agent.smartrecruiters.base-url:https://api.smartrecruiters.com}") String baseUrl,
            @Value("${jobs.discovery.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent,
            @Value("${company.discovery.enabled:false}") boolean enabled) {
        super(baseUrl, userAgent);
        this.enabled = enabled;
    }

    @Override public String name() { return "smartrecruiters"; }

    @Override public boolean isConfigured() { return enabled; }

    @Override
    public Optional<DiscoveredCandidate> probe(String candidateSlug) {
        if (candidateSlug == null || candidateSlug.isBlank()) return Optional.empty();
        try {
            Map<String, Object> body = client.get()
                    .uri("/v1/companies/{companyId}/postings", candidateSlug)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(Duration.ofSeconds(10))
                    .block();
            return mapResponse(body, candidateSlug);
        } catch (Exception e) {
            log.debug("smartrecruiters company-probe miss for '{}': {}", candidateSlug, e.toString());
            return Optional.empty();
        }
    }

    /** Package-private for direct unit testing without standing up a mock HTTP server. */
    Optional<DiscoveredCandidate> mapResponse(Map<String, Object> body, String slug) {
        if (body == null || !(body.get("content") instanceof List<?> content)) return Optional.empty();
        String companyName = slug;
        if (!content.isEmpty() && content.get(0) instanceof Map<?, ?> first) {
            Map<String, Object> company = asMap(((Map<String, Object>) first).get("company"));
            String realName = str(company.get("name"));
            if (realName != null && !realName.isBlank()) companyName = realName;
        }
        return Optional.of(new DiscoveredCandidate(
                "SMARTRECRUITERS", companyName, "https://jobs.smartrecruiters.com/" + slug, null, null, null));
    }
}
