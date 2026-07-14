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
 * Phase 5.2 — AI Dev Jobs (aidevboard.com) adapter. Real, working, keyless public REST API
 * (verified {@code GET https://aidevboard.com/api/v1/jobs?limit=N} returns 200 with no auth —
 * "Public read endpoints are open and free" per {@code /docs}). Ships dark: joins the discovery
 * chain only once {@link #isConfigured()} — i.e. {@code career.discovery.ai-dev-jobs.enabled} —
 * is flipped true, matching the Ashby/SmartRecruiters keyless-but-flag-gated convention rather
 * than RemoteOK/Arbeitnow's always-on one, since this is a new, unvetted source.
 */
@Component
public class AiDevJobsProvider extends AbstractWebJobProvider {

    private static final Logger log = LoggerFactory.getLogger(AiDevJobsProvider.class);

    private final boolean enabled;
    private final int limit;

    public AiDevJobsProvider(
            @Value("${career.discovery.ai-dev-jobs.enabled:false}") boolean enabled,
            @Value("${career.discovery.ai-dev-jobs.base-url:https://aidevboard.com}") String baseUrl,
            @Value("${career.discovery.ai-dev-jobs.limit:50}") int limit,
            @Value("${jobs.discovery.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent) {
        super(baseUrl, userAgent);
        this.enabled = enabled;
        this.limit = Math.min(Math.max(limit, 1), 50); // API caps at 50/page
    }

    @Override public String name() { return "ai-dev-jobs"; }

    @Override public boolean isConfigured() { return enabled; }

    @Override
    public List<RawJob> fetch() {
        Map<String, Object> body = client.get()
                .uri(uriBuilder -> uriBuilder.path("/api/v1/jobs").queryParam("limit", limit).build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(20))
                .block();

        List<RawJob> jobs = new ArrayList<>();
        if (body == null || !(body.get("jobs") instanceof List<?> list)) return jobs;

        for (Object o : list) {
            Map<String, Object> m = asMap(o);
            String externalId = str(m.get("id"));
            if (externalId == null) continue;
            jobs.add(new RawJob(
                    externalId,
                    str(m.get("title")),
                    str(m.get("company_name")),
                    str(m.get("location")),
                    null, null,             // country/city inferred by the normalizer
                    remoteFrom(str(m.get("workplace"))),
                    num(m.get("salary_min")),
                    num(m.get("salary_max")),
                    "USD",                  // API docs: salary figures are USD/year
                    str(m.get("description")),
                    stringList(m.get("tags")),
                    str(m.getOrDefault("apply_url", m.get("url"))),
                    firstNonNull(isoInstant(m.get("published_at")), isoInstant(m.get("created_at"))),
                    m));    // capture raw payload for the Job Lake (ignored by legacy normalizer)
        }
        log.info("ai-dev-jobs: fetched {} jobs", jobs.size());
        return jobs;
    }

    /** "remote" -> true, "onsite" -> false, "hybrid"/unknown -> null (ambiguous, left to normalizer). */
    private static Boolean remoteFrom(String workplace) {
        if (workplace == null) return null;
        return switch (workplace.toLowerCase()) {
            case "remote" -> Boolean.TRUE;
            case "onsite" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static java.time.Instant firstNonNull(java.time.Instant a, java.time.Instant b) {
        return a != null ? a : b;
    }
}
