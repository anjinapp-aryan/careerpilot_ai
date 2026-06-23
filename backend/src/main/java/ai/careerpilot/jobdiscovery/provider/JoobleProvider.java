package ai.careerpilot.jobdiscovery.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Jooble adapter — keyed aggregator at {@code https://jooble.org}. Requires a single API
 * key (free tier), so {@link #isConfigured()} is false until {@code JOOBLE_API_KEY} is set
 * and the provider is skipped. Jooble is a POST API: {@code POST /api/{key}} with a JSON
 * body of {@code keywords}/{@code location}. Country/city are inferred by the normalizer
 * from the free-text {@code location}; salary is a free-text string when present.
 */
@Component
public class JoobleProvider extends AbstractWebJobProvider {

    private static final Logger log = LoggerFactory.getLogger(JoobleProvider.class);

    private final String apiKey;
    private final List<String> keywords;

    public JoobleProvider(
            @Value("${jobs.discovery.providers.jooble.base-url:https://jooble.org}") String baseUrl,
            @Value("${jobs.discovery.providers.jooble.api-key:}") String apiKey,
            @Value("${jobs.discovery.providers.jooble.keywords:software engineer,data engineer}") String keywords,
            @Value("${jobs.discovery.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent) {
        super(baseUrl, userAgent);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.keywords = java.util.Arrays.stream(keywords.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @Override public String name() { return "jooble"; }

    @Override public boolean isConfigured() { return !apiKey.isBlank(); }

    @Override
    public List<RawJob> fetch() {
        List<RawJob> jobs = new ArrayList<>();
        for (String kw : keywords) {
            try {
                jobs.addAll(fetchKeyword(kw));
            } catch (Exception e) {
                log.warn("jooble: keyword '{}' fetch failed: {}", kw, e.toString());
            }
        }
        log.info("jooble: fetched {} jobs across {} keyword queries", jobs.size(), keywords.size());
        return jobs;
    }

    private List<RawJob> fetchKeyword(String keyword) {
        Map<String, Object> body = client.post().uri("/api/{key}", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("keywords", keyword))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(20))
                .block();

        List<RawJob> jobs = new ArrayList<>();
        if (body == null || !(body.get("jobs") instanceof List<?> list)) return jobs;

        for (Object o : list) {
            Map<String, Object> m = asMap(o);
            // Jooble lacks a stable numeric id on every plan; key off the link, fall back to id.
            String externalId = str(m.getOrDefault("id", m.get("link")));
            if (externalId == null) continue;
            String location = str(m.get("location"));
            boolean remote = location != null && location.toLowerCase().contains("remote");
            jobs.add(new RawJob(
                    externalId,
                    str(m.get("title")),
                    str(m.get("company")),
                    location,
                    null, null,             // country/city inferred by the normalizer
                    remote ? Boolean.TRUE : null,
                    null, null, null,       // salary is free-text only; left to the description
                    str(m.get("snippet")),
                    List.of(),              // skills inferred by the normalizer
                    str(m.get("link")),
                    isoInstant(m.get("updated"))));
        }
        return jobs;
    }
}
