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
 * RemoteOK adapter — keyless public JSON feed at {@code https://remoteok.com/api}.
 * The first array element is a legal/metadata object and is skipped. RemoteOK rejects
 * requests without a real {@code User-Agent}, so one is always sent. All jobs here are
 * remote by definition.
 */
@Component
public class RemoteOkProvider extends AbstractWebJobProvider {

    private static final Logger log = LoggerFactory.getLogger(RemoteOkProvider.class);

    public RemoteOkProvider(
            @Value("${jobs.discovery.providers.remoteok.base-url:https://remoteok.com}") String baseUrl,
            @Value("${jobs.discovery.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent) {
        super(baseUrl, userAgent);
    }

    @Override public String name() { return "remoteok"; }

    @Override public boolean isConfigured() { return true; } // keyless

    @Override
    public List<RawJob> fetch() {
        List<Map<String, Object>> raw = client.get().uri("/api")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .timeout(Duration.ofSeconds(20))
                .block();

        List<RawJob> jobs = new ArrayList<>();
        if (raw == null) return jobs;
        for (Map<String, Object> m : raw) {
            // skip the leading {"legal": ...} metadata element
            if (m.get("position") == null && m.get("id") == null) continue;
            String externalId = str(m.get("id"));
            if (externalId == null) continue;
            String location = str(m.getOrDefault("location", "Remote"));
            jobs.add(new RawJob(
                    externalId,
                    str(m.get("position")),
                    str(m.get("company")),
                    location,
                    null, null,            // country/city inferred by the normalizer
                    Boolean.TRUE,          // RemoteOK is remote-only
                    num(m.get("salary_min")),
                    num(m.get("salary_max")),
                    "USD",
                    str(m.get("description")),
                    stringList(m.get("tags")),
                    str(m.getOrDefault("url", m.get("apply_url"))),
                    epochSeconds(m.get("epoch"))));
        }
        log.info("remoteok: fetched {} jobs", jobs.size());
        return jobs;
    }
}
