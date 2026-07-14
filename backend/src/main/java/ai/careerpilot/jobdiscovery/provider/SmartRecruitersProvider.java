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
 * SmartRecruiters public Posting API adapter, keyless public JSON at
 * {@code https://api.smartrecruiters.com/v1/companies/{companyId}/postings}, returning a
 * {@code content} array of postings. One call per configured company id returns that company's
 * current open postings page (first page only — SmartRecruiters paginates via
 * {@code offset}/{@code limit}, not implemented here to keep parity with Greenhouse/Lever's
 * single-call-per-token shape). Dark by default: {@link #isConfigured()} requires both the
 * master flag and at least one company id.
 *
 * <p>Mirrors {@link GreenhouseProvider}'s shape exactly: same {@code AbstractWebJobProvider}
 * base, same per-company try/catch isolation, same throttle-between-calls pattern, same
 * defensive {@code asMap}/{@code str} null-handling. Reuses {@link RawJob}/{@code JobNormalizer}
 * rather than a parallel normalizer.
 */
@Component
public class SmartRecruitersProvider extends AbstractWebJobProvider {

    private static final Logger log = LoggerFactory.getLogger(SmartRecruitersProvider.class);

    private final boolean enabled;
    private final List<String> companyIds;
    private final int throttleMs;

    public SmartRecruitersProvider(
            @Value("${career.discovery.smartrecruiters.base-url:https://api.smartrecruiters.com}") String baseUrl,
            @Value("${jobs.discovery.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent,
            @Value("${career.discovery.smartrecruiters.enabled:false}") boolean enabled,
            @Value("${career.discovery.smartrecruiters.companies:}") String companies,
            @Value("${career.discovery.smartrecruiters.throttle-ms:300}") int throttleMs) {
        super(baseUrl, userAgent);
        this.enabled = enabled;
        this.companyIds = splitCsv(companies);
        this.throttleMs = throttleMs;
    }

    @Override public String name() { return "smartrecruiters"; }

    @Override public boolean isConfigured() { return enabled && !companyIds.isEmpty(); }

    @Override
    public List<RawJob> fetch() {
        List<RawJob> jobs = new ArrayList<>();
        for (String companyId : companyIds) {
            try {
                jobs.addAll(fetchCompany(companyId));
            } catch (Exception e) {
                log.warn("smartrecruiters: company {} fetch failed: {}", companyId, e.toString());
            }
            throttle();
        }
        log.info("smartrecruiters: fetched {} jobs across {} companies", jobs.size(), companyIds.size());
        return jobs;
    }

    private List<RawJob> fetchCompany(String companyId) {
        Map<String, Object> raw = client.get()
                .uri("/v1/companies/{companyId}/postings", companyId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(20))
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1)))
                .block();

        List<RawJob> jobs = new ArrayList<>();
        if (raw == null) return jobs;
        Object contentObj = raw.get("content");
        if (!(contentObj instanceof List<?> list)) return jobs;
        for (Object o : list) {
            try {
                RawJob job = mapPosting(asMap(o), companyId);
                if (job != null) jobs.add(job);
            } catch (Exception e) {
                log.warn("smartrecruiters: company {} skipping malformed posting: {}", companyId, e.toString());
            }
        }
        return jobs;
    }

    /** Package-private for direct unit testing without standing up a mock HTTP server. */
    RawJob mapPosting(Map<String, Object> m, String companyId) {
        String externalId = str(m.get("id"));
        if (externalId == null) return null;

        Map<String, Object> location = asMap(m.get("location"));
        String city = str(location.get("city"));
        String country = str(location.get("country"));
        String region = str(location.get("region"));
        boolean remote = Boolean.TRUE.equals(bool(location.get("remote")));
        String locationStr = firstNonBlank(joinLocation(city, region, country), remote ? "Remote" : null);

        Map<String, Object> jobAd = asMap(m.get("jobAd"));
        Map<String, Object> sections = asMap(jobAd.get("sections"));
        String description = extractJobAdText(sections);

        Map<String, Object> company = asMap(m.get("company"));
        String companyName = firstNonBlank(str(company.get("name")), companyId);

        String sourceUrl = str(m.get("applyUrl"));
        if (sourceUrl == null) {
            Map<String, Object> ref = asMap(m.get("ref"));
            sourceUrl = str(ref.get("jobAdUrl"));
        }

        return new RawJob(
                externalId,
                str(m.get("name")),
                companyName,
                locationStr,
                country, city,
                remote,
                null, null, null,
                description,
                List.of(),
                sourceUrl,
                isoInstant(m.get("releasedDate")),
                m);
    }

    @SuppressWarnings("unchecked")
    private String extractJobAdText(Map<String, Object> sections) {
        if (sections == null || sections.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Object v : sections.values()) {
            if (!(v instanceof Map<?, ?> section)) continue;
            Object text = ((Map<String, Object>) section).get("text");
            if (text != null) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(stripHtml(String.valueOf(text)));
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String joinLocation(String city, String region, String country) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] {city, region, country}) {
            if (part == null || part.isBlank()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(part);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
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
