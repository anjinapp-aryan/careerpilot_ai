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
 * Phase 5D — Lever Postings API adapter, keyless public JSON at
 * {@code https://api.lever.co/v0/postings/{company}?mode=json}. One call per configured company
 * slug returns that company's full open-postings list. Dark by default: {@link #isConfigured()}
 * requires both the master flag and at least one company slug.
 */
@Component
public class LeverProvider extends AbstractWebJobProvider {

    private static final Logger log = LoggerFactory.getLogger(LeverProvider.class);

    private final boolean enabled;
    private final List<String> companies;
    private final int throttleMs;

    public LeverProvider(
            @Value("${career.discovery.lever.base-url:https://api.lever.co/v0}") String baseUrl,
            @Value("${jobs.discovery.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent,
            @Value("${career.discovery.lever.enabled:false}") boolean enabled,
            @Value("${career.discovery.lever.companies:}") String companies,
            @Value("${career.discovery.lever.throttle-ms:300}") int throttleMs) {
        super(baseUrl, userAgent);
        this.enabled = enabled;
        this.companies = splitCsv(companies);
        this.throttleMs = throttleMs;
    }

    @Override public String name() { return "lever"; }

    @Override public boolean isConfigured() { return enabled && !companies.isEmpty(); }

    @Override
    public List<RawJob> fetch() {
        List<RawJob> jobs = new ArrayList<>();
        for (String company : companies) {
            try {
                jobs.addAll(fetchCompany(company));
            } catch (Exception e) {
                log.warn("lever: company {} fetch failed: {}", company, e.toString());
            }
            throttle();
        }
        log.info("lever: fetched {} jobs across {} companies", jobs.size(), companies.size());
        return jobs;
    }

    private List<RawJob> fetchCompany(String company) {
        List<Map<String, Object>> raw = client.get()
                .uri("/postings/{company}?mode=json", company)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .timeout(Duration.ofSeconds(20))
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)))
                .block();

        List<RawJob> jobs = new ArrayList<>();
        if (raw == null) return jobs;
        for (Map<String, Object> m : raw) {
            String externalId = str(m.get("id"));
            if (externalId == null) continue;
            Map<String, Object> categories = asMap(m.get("categories"));
            boolean remote = "Remote".equalsIgnoreCase(str(categories.get("location")))
                    || "true".equalsIgnoreCase(str(categories.get("allLocations")));
            jobs.add(new RawJob(
                    externalId,
                    str(m.get("text")),
                    company,
                    str(categories.get("location")),
                    null, null,
                    remote,
                    null, null, null,
                    str(m.get("descriptionPlain")),
                    stringList(m.get("tags")),
                    str(m.get("hostedUrl")),
                    epochMillis(m.get("createdAt")),
                    m));
        }
        return jobs;
    }

    private static java.time.Instant epochMillis(Object v) {
        if (v == null) return null;
        try {
            long ms = (v instanceof Number n) ? n.longValue() : Long.parseLong(String.valueOf(v).trim());
            return java.time.Instant.ofEpochMilli(ms);
        } catch (Exception e) {
            return null;
        }
    }

    private void throttle() {
        if (throttleMs <= 0) return;
        try {
            Thread.sleep(throttleMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return List.of(csv.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
