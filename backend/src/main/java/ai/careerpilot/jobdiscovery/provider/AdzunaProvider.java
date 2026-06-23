package ai.careerpilot.jobdiscovery.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adzuna adapter — keyed aggregator at {@code https://api.adzuna.com}. Requires an
 * {@code app_id}/{@code app_key} pair (free developer tier), so {@link #isConfigured()}
 * is false until both are set and the provider is simply skipped. One page of results is
 * pulled per configured country code so the Domestic/International split has structured
 * country data straight from the source (no inference needed).
 *
 * <p>Endpoint: {@code GET /v1/api/jobs/{country}/search/1?app_id=..&app_key=..&results_per_page=N}.
 */
@Component
public class AdzunaProvider extends AbstractWebJobProvider {

    private static final Logger log = LoggerFactory.getLogger(AdzunaProvider.class);

    /** Adzuna country code → display name used for the Domestic/International split. */
    private static final Map<String, String> COUNTRY_NAMES = Map.ofEntries(
            Map.entry("gb", "United Kingdom"), Map.entry("us", "United States"),
            Map.entry("in", "India"), Map.entry("de", "Germany"),
            Map.entry("ca", "Canada"), Map.entry("au", "Australia"),
            Map.entry("nl", "Netherlands"), Map.entry("sg", "Singapore"),
            Map.entry("fr", "France"), Map.entry("at", "Austria"),
            Map.entry("br", "Brazil"), Map.entry("it", "Italy"),
            Map.entry("nz", "New Zealand"), Map.entry("pl", "Poland"),
            Map.entry("za", "South Africa"), Map.entry("es", "Spain"));

    private final String appId;
    private final String appKey;
    private final List<String> countries;
    private final int resultsPerPage;

    public AdzunaProvider(
            @Value("${jobs.discovery.providers.adzuna.base-url:https://api.adzuna.com}") String baseUrl,
            @Value("${jobs.discovery.providers.adzuna.app-id:}") String appId,
            @Value("${jobs.discovery.providers.adzuna.app-key:}") String appKey,
            @Value("${jobs.discovery.providers.adzuna.countries:gb,us,in}") String countries,
            @Value("${jobs.discovery.providers.adzuna.results-per-page:50}") int resultsPerPage,
            @Value("${jobs.discovery.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent) {
        super(baseUrl, userAgent);
        this.appId = appId == null ? "" : appId.trim();
        this.appKey = appKey == null ? "" : appKey.trim();
        this.countries = java.util.Arrays.stream(countries.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(String::toLowerCase).toList();
        this.resultsPerPage = Math.min(Math.max(resultsPerPage, 1), 50);
    }

    @Override public String name() { return "adzuna"; }

    @Override public boolean isConfigured() { return !appId.isBlank() && !appKey.isBlank(); }

    @Override
    public List<RawJob> fetch() {
        List<RawJob> jobs = new ArrayList<>();
        for (String country : countries) {
            try {
                jobs.addAll(fetchCountry(country));
            } catch (Exception e) {
                // Isolate a single bad country; the rest of the run continues.
                log.warn("adzuna: country {} fetch failed: {}", country, e.toString());
            }
        }
        log.info("adzuna: fetched {} jobs across {} countries", jobs.size(), countries.size());
        return jobs;
    }

    private List<RawJob> fetchCountry(String country) {
        String uri = UriComponentsBuilder.fromPath("/v1/api/jobs/{country}/search/1")
                .queryParam("app_id", appId)
                .queryParam("app_key", appKey)
                .queryParam("results_per_page", resultsPerPage)
                .queryParam("content-type", "application/json")
                .buildAndExpand(country).toUriString();

        Map<String, Object> body = client.get().uri(uri)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(20))
                .block();

        List<RawJob> jobs = new ArrayList<>();
        if (body == null || !(body.get("results") instanceof List<?> results)) return jobs;

        String countryName = COUNTRY_NAMES.getOrDefault(country, null);
        for (Object o : results) {
            Map<String, Object> m = asMap(o);
            String externalId = str(m.get("id"));
            if (externalId == null) continue;
            Map<String, Object> loc = asMap(m.get("location"));
            String locationName = str(loc.get("display_name"));
            jobs.add(new RawJob(
                    externalId,
                    str(m.get("title")),
                    str(asMap(m.get("company")).get("display_name")),
                    locationName,
                    countryName,
                    cityFromArea(loc),
                    null,                       // Adzuna doesn't flag remote structurally
                    num(m.get("salary_min")),
                    num(m.get("salary_max")),
                    currencyFor(country),
                    str(m.get("description")),
                    List.of(),                  // skills inferred by the normalizer
                    str(m.get("redirect_url")),
                    isoInstant(m.get("created"))));
        }
        return jobs;
    }

    /** Adzuna's {@code location.area} is a country→…→city hierarchy; the last element is the city. */
    private static String cityFromArea(Map<String, Object> loc) {
        if (loc.get("area") instanceof List<?> area && !area.isEmpty()) {
            Object last = area.get(area.size() - 1);
            return last == null ? null : String.valueOf(last);
        }
        return null;
    }

    private static String currencyFor(String country) {
        return switch (country) {
            case "gb" -> "GBP";
            case "us", "ca", "au", "nz", "sg" -> "USD";
            case "in" -> "INR";
            case "de", "nl", "fr", "at", "it", "es", "pl" -> "EUR";
            case "br" -> "BRL";
            case "za" -> "ZAR";
            default -> null;
        };
    }
}
