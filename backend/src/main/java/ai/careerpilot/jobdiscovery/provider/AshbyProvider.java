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
 * Ashby public Job Board API adapter, keyless public JSON at
 * {@code https://api.ashbyhq.com/posting-api/job-board/{orgSlug}}, returning a {@code jobs}
 * array. One call per configured org slug returns that company's full open-roles list (no
 * further pagination on this endpoint). Dark by default: {@link #isConfigured()} requires both
 * the master flag and at least one org slug — with stock config this provider fetches nothing.
 *
 * <p>Mirrors {@link GreenhouseProvider}'s shape exactly: same {@code AbstractWebJobProvider}
 * base, same per-board try/catch isolation, same throttle-between-calls pattern, same defensive
 * {@code asMap}/{@code str} null-handling. Reuses {@link RawJob}/{@code JobNormalizer} rather
 * than a parallel normalizer.
 */
@Component
public class AshbyProvider extends AbstractWebJobProvider {

    private static final Logger log = LoggerFactory.getLogger(AshbyProvider.class);

    private final boolean enabled;
    private final List<String> orgSlugs;
    private final int throttleMs;

    public AshbyProvider(
            @Value("${career.discovery.ashby.base-url:https://api.ashbyhq.com}") String baseUrl,
            @Value("${jobs.discovery.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent,
            @Value("${career.discovery.ashby.enabled:false}") boolean enabled,
            @Value("${career.discovery.ashby.orgs:}") String orgs,
            @Value("${career.discovery.ashby.throttle-ms:300}") int throttleMs) {
        super(baseUrl, userAgent);
        this.enabled = enabled;
        this.orgSlugs = splitCsv(orgs);
        this.throttleMs = throttleMs;
    }

    @Override public String name() { return "ashby"; }

    @Override public boolean isConfigured() { return enabled && !orgSlugs.isEmpty(); }

    @Override
    public List<RawJob> fetch() {
        List<RawJob> jobs = new ArrayList<>();
        for (String org : orgSlugs) {
            try {
                jobs.addAll(fetchOrg(org));
            } catch (Exception e) {
                log.warn("ashby: org {} fetch failed: {}", org, e.toString());
            }
            throttle();
        }
        log.info("ashby: fetched {} jobs across {} orgs", jobs.size(), orgSlugs.size());
        return jobs;
    }

    private List<RawJob> fetchOrg(String org) {
        Map<String, Object> raw = client.get()
                .uri("/posting-api/job-board/{org}", org)
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
            try {
                RawJob job = mapJob(asMap(o), org);
                if (job != null) jobs.add(job);
            } catch (Exception e) {
                log.warn("ashby: org {} skipping malformed job record: {}", org, e.toString());
            }
        }
        return jobs;
    }

    /** Package-private for direct unit testing without standing up a mock HTTP server. */
    RawJob mapJob(Map<String, Object> m, String org) {
        String externalId = str(m.get("id"));
        if (externalId == null) return null;

        String location = str(m.get("location"));
        if (location == null) {
            // Some org configs surface address as a nested object instead of a flat string.
            Map<String, Object> address = asMap(m.get("address"));
            Map<String, Object> postalAddress = asMap(address.get("postalAddress"));
            String city = str(postalAddress.get("addressLocality"));
            String region = str(postalAddress.get("addressRegion"));
            if (city != null || region != null) {
                location = (city == null ? "" : city) + (city != null && region != null ? ", " : "") + (region == null ? "" : region);
            }
        }

        boolean remote = Boolean.TRUE.equals(bool(m.get("isRemote")))
                || (location != null && location.toLowerCase().contains("remote"));

        return new RawJob(
                externalId,
                str(m.get("title")),
                org,
                location,
                null, null,
                remote,
                null, null, null,
                stripHtml(str(m.get("descriptionHtml"))),
                List.of(),
                str(m.get("jobUrl")),
                isoInstant(m.get("publishedAt")),
                m);
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
