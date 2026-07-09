package ai.careerpilot.jobdiscovery.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 5C — Greenhouse Job Board API adapter, keyless public JSON at
 * {@code https://boards-api.greenhouse.io/v1/boards/{board}/jobs?content=true}. One call per
 * configured board token returns that company's full open-roles list (no further pagination on
 * this endpoint). Dark by default: {@link #isConfigured()} requires both the master flag and at
 * least one board token — with stock config this provider fetches nothing.
 *
 * <p>Reuses {@link ai.careerpilot.jobdiscovery.JobNormalizer} for title/location/skill
 * normalization (via {@link RawJob}) rather than a parallel normalizer, matching the existing
 * {@code RemoteOkProvider}/{@code ArbeitnowProvider} shape.
 */
@Component
public class GreenhouseProvider extends AbstractWebJobProvider {

    private static final Logger log = LoggerFactory.getLogger(GreenhouseProvider.class);

    private final boolean enabled;
    private final List<String> boardTokens;
    private final int throttleMs;

    public GreenhouseProvider(
            @Value("${career.discovery.greenhouse.base-url:https://boards-api.greenhouse.io/v1}") String baseUrl,
            @Value("${jobs.discovery.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent,
            @Value("${career.discovery.greenhouse.enabled:false}") boolean enabled,
            @Value("${career.discovery.greenhouse.boards:}") String boards,
            @Value("${career.discovery.greenhouse.throttle-ms:300}") int throttleMs) {
        super(baseUrl, userAgent);
        this.enabled = enabled;
        this.boardTokens = splitCsv(boards);
        this.throttleMs = throttleMs;
    }

    @Override public String name() { return "greenhouse"; }

    @Override public boolean isConfigured() { return enabled && !boardTokens.isEmpty(); }

    @Override
    public List<RawJob> fetch() {
        List<RawJob> jobs = new ArrayList<>();
        for (String board : boardTokens) {
            try {
                jobs.addAll(fetchBoard(board));
            } catch (Exception e) {
                log.warn("greenhouse: board {} fetch failed: {}", board, e.toString());
            }
            throttle();
        }
        log.info("greenhouse: fetched {} jobs across {} boards", jobs.size(), boardTokens.size());
        return jobs;
    }

    private List<RawJob> fetchBoard(String board) {
        Map<String, Object> raw = client.get()
                .uri("/boards/{board}/jobs?content=true", board)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(20))
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)))
                .block();

        List<RawJob> jobs = new ArrayList<>();
        if (raw == null) return jobs;
        Object jobsObj = raw.get("jobs");
        if (!(jobsObj instanceof List<?> list)) return jobs;
        for (Object o : list) {
            Map<String, Object> m = asMap(o);
            String externalId = str(m.get("id"));
            if (externalId == null) continue;
            Map<String, Object> location = asMap(m.get("location"));
            jobs.add(new RawJob(
                    externalId,
                    str(m.get("title")),
                    board,
                    str(location.get("name")),
                    null, null,
                    Boolean.FALSE,
                    null, null, null,
                    stripHtml(str(m.get("content"))),
                    List.of(),
                    str(m.get("absolute_url")),
                    isoInstant(m.get("updated_at")),
                    m));
        }
        return jobs;
    }

    private void throttle() {
        if (throttleMs <= 0) return;
        try {
            Thread.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String stripHtml(String html) {
        return html == null ? null : html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return List.of(csv.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
