package ai.careerpilot.api;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.AiGatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Temporary diagnostics endpoint for AI Gateway troubleshooting.
 * Verifies API keys, models, connectivity, and routing.
 */
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsController.class);

    private final AiGatewayService gateway;
    private final AiGatewayProperties props;

    @Value("${GEMINI_API_KEY:}")
    private String geminiKey;

    @Value("${NVIDIA_API_KEY:}")
    private String nvidiaKey;

    public DiagnosticsController(AiGatewayService gateway, AiGatewayProperties props) {
        this.gateway = gateway;
        this.props = props;
    }

    @GetMapping("/ai")
    public Map<String, Object> aiDiagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();

        // API Keys loaded
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("gemini_loaded", !geminiKey.isBlank());
        keys.put("nvidia_loaded", !nvidiaKey.isBlank());
        result.put("api_keys", keys);

        // Models configured
        Map<String, String> models = new LinkedHashMap<>();
        props.getProviders().forEach((name, provider) -> {
            if (provider.getModel() != null) {
                models.put(name, provider.getModel());
            }
        });
        result.put("models", models);

        // Base URLs configured
        Map<String, String> baseUrls = new LinkedHashMap<>();
        props.getProviders().forEach((name, provider) -> {
            if (provider.getBaseUrl() != null) {
                baseUrls.put(name, provider.getBaseUrl());
            }
        });
        result.put("base_urls", baseUrls);

        // Gateway health
        result.put("gateway_health", gateway.health());

        // Gateway stats
        result.put("gateway_stats", gateway.stats());

        // Provider order
        result.put("provider_order", props.getOrder());
        result.put("primary_provider", props.getPrimary());

        // Default temperature
        result.put("default_temperature", props.getDefaultTemperature());

        log.info("AI Diagnostics endpoint accessed");
        return result;
    }
}
