package ai.careerpilot.jobdiscovery.discovery;

import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

/**
 * Shared WebClient construction for {@link CompanySource} implementations — mirrors {@link
 * ai.careerpilot.jobdiscovery.provider.AbstractWebJobProvider} (same headers/timeout style) but
 * kept as a separate, smaller base class since a probe is a single bounded GET, not a paginated
 * job-list fetch, and does not need that class's numeric/date parsing helpers.
 */
abstract class AbstractWebCompanySource {

    protected final WebClient client;

    protected AbstractWebCompanySource(String baseUrl, String userAgent) {
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
    }

    protected static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    protected static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
