package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.ai.Capability;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Discovers OpenRouter's live model catalog ({@code GET /v1/models}) once at startup and
 * resolves {@link Capability} → ordered candidate model ids from {@code
 * ai.gateway.open-router.capabilities} (config-driven — no model id is ever hardcoded here).
 *
 * <p><b>Startup safety</b>: discovery runs on {@link ApplicationReadyEvent} (i.e. strictly
 * <em>after</em> the Spring context has already finished starting), wrapped in a full
 * try/catch with a short timeout. A failed, slow, or disabled discovery call can therefore
 * never block or fail application startup — it just leaves the catalog empty, in which case
 * {@link #candidatesFor(Capability)} falls back to trusting the configured preferred list
 * as-is (unvalidated) rather than filtering anything out.</p>
 */
@Service
public class OpenRouterModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterModelRegistry.class);
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(10);

    private final AiGatewayProperties props;
    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile Map<String, OpenRouterModelMetadata> catalog = Map.of();
    private volatile boolean discoverySucceeded = false;

    public OpenRouterModelRegistry(AiGatewayProperties props) {
        this.props = props;
        AiGatewayProperties.Provider cfg = props.provider("openrouter");
        this.client = WebClient.builder()
                .baseUrl(cfg.getBaseUrl() == null ? "https://openrouter.ai/api/v1" : cfg.getBaseUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void discoverOnStartup() {
        if (!props.getOpenRouter().isAutoDiscoverModels()) {
            log.info("OpenRouter model auto-discovery disabled (ai.gateway.open-router.auto-discover-models=false) "
                    + "— trusting configured preferred lists without live validation.");
            return;
        }
        refreshCatalog();
    }

    /** Re-fetches the live catalog. Never throws — logs and leaves the previous catalog on failure. */
    public void refreshCatalog() {
        AiGatewayProperties.Provider cfg = props.provider("openrouter");
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            log.info("OpenRouter model discovery skipped — no OPENROUTER_API_KEY configured.");
            return;
        }
        try {
            String raw = client.get()
                    .uri("/models")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.getApiKey())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(DISCOVERY_TIMEOUT)
                    .block(DISCOVERY_TIMEOUT);
            Map<String, OpenRouterModelMetadata> parsed = parseCatalog(raw);
            this.catalog = parsed;
            this.discoverySucceeded = true;
            log.info("OpenRouter model discovery succeeded — {} models in live catalog.", parsed.size());
            warnAboutMissingConfiguredModels();
        } catch (Exception e) {
            // Deliberately broad: any discovery failure (timeout, network, parse error, 401, ...)
            // degrades to "trust config as-is" rather than propagating — see class javadoc.
            log.warn("OpenRouter model discovery failed ({}) — falling back to configured preferred "
                    + "lists without live validation. Application startup is unaffected.", e.toString());
        }
    }

    private Map<String, OpenRouterModelMetadata> parseCatalog(String raw) throws Exception {
        Map<String, OpenRouterModelMetadata> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return out;
        JsonNode root = mapper.readTree(raw);
        for (JsonNode entry : root.path("data")) {
            OpenRouterModelMetadata meta = OpenRouterModelMetadata.fromCatalogEntry(entry);
            if (meta.id() != null) {
                out.put(meta.id(), meta);
            }
        }
        return out;
    }

    private void warnAboutMissingConfiguredModels() {
        if (!props.getOpenRouter().isValidateConfiguredModels()) return;
        for (Map.Entry<String, AiGatewayProperties.CapabilityPreference> e : props.getOpenRouter().getCapabilities().entrySet()) {
            for (String modelId : e.getValue().getPreferred()) {
                if (!catalog.containsKey(modelId)) {
                    log.warn("OpenRouter model '{}' (configured for capability '{}') was not found in the "
                            + "live catalog — it will be skipped for that capability until it reappears "
                            + "or the config is corrected. This does not affect application startup.",
                            modelId, e.getKey());
                }
            }
        }
    }

    /**
     * Ordered candidate model ids for a capability: the configured preferred list, filtered to
     * catalog-confirmed entries only if discovery succeeded AND {@code validateConfiguredModels}
     * is true. If discovery never ran/failed, or validation is off, returns the configured list
     * unfiltered — an individual bad model id still fails gracefully at call time (skipped, next
     * candidate tried) exactly like any other provider failure.
     */
    public List<String> candidatesFor(Capability capability) {
        AiGatewayProperties.CapabilityPreference pref = props.getOpenRouter().getCapabilities()
                .get(capability.name().toLowerCase());
        List<String> configured = pref == null ? List.of() : pref.getPreferred();

        if (!discoverySucceeded || !props.getOpenRouter().isValidateConfiguredModels() || catalog.isEmpty()) {
            return configured;
        }
        List<String> confirmed = new ArrayList<>();
        for (String modelId : configured) {
            if (catalog.containsKey(modelId)) {
                confirmed.add(modelId);
            }
        }
        return confirmed;
    }

    /**
     * Every configured model id across every capability, in capability-declaration order with
     * duplicates removed — used as the default candidate pool for a capability-agnostic call.
     */
    public List<String> allConfiguredModelsDeduplicated() {
        List<String> out = new ArrayList<>();
        for (AiGatewayProperties.CapabilityPreference pref : props.getOpenRouter().getCapabilities().values()) {
            for (String modelId : pref.getPreferred()) {
                if (!out.contains(modelId)) {
                    out.add(modelId);
                }
            }
        }
        return out;
    }

    public Optional<OpenRouterModelMetadata> metadataFor(String modelId) {
        return Optional.ofNullable(catalog.get(modelId));
    }

    public boolean discoverySucceeded() {
        return discoverySucceeded;
    }

    public int catalogSize() {
        return catalog.size();
    }
}
