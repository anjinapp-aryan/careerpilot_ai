package ai.careerpilot.jobdiscovery.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Arbeitnow adapter — keyless public job-board feed at
 * {@code https://www.arbeitnow.com/api/job-board-api}. Response is {@code {"data": [...]}};
 * descriptions are HTML (cleaned by the normalizer). Many listings are European, so the
 * normalizer's country inference drives the Domestic/International split.
 */
@Component
public class ArbeitnowProvider extends AbstractWebJobProvider {

    private static final Logger log = LoggerFactory.getLogger(ArbeitnowProvider.class);

    public ArbeitnowProvider(
            @Value("${jobs.discovery.providers.arbeitnow.base-url:https://www.arbeitnow.com}") String baseUrl,
            @Value("${jobs.discovery.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent) {
        super(baseUrl, userAgent);
    }

    @Override public String name() { return "arbeitnow"; }

    @Override public boolean isConfigured() { return true; } // keyless

    @Override
    public List<RawJob> fetch() {
        Map<String, Object> body = client.get().uri("/api/job-board-api")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(20))
                .block();

        List<RawJob> jobs = new ArrayList<>();
        if (body == null) return jobs;
        Object data = body.get("data");
        if (!(data instanceof List<?> list)) return jobs;

        for (Object o : list) {
            Map<String, Object> m = asMap(o);
            String externalId = str(m.getOrDefault("slug", m.get("url")));
            if (externalId == null) continue;
            jobs.add(new RawJob(
                    externalId,
                    str(m.get("title")),
                    str(m.get("company_name")),
                    str(m.get("location")),
                    null, null,            // country/city inferred by the normalizer
                    bool(m.get("remote")),
                    null, null, null,      // Arbeitnow does not expose structured salary
                    str(m.get("description")),
                    stringList(m.get("tags")),
                    str(m.get("url")),
                    epochSeconds(m.get("created_at"))));
        }
        log.info("arbeitnow: fetched {} jobs", jobs.size());
        return jobs;
    }
}
