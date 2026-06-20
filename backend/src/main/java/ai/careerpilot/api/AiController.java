package ai.careerpilot.api;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ProviderHealthTracker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Operational endpoints for the AI Gateway.
 *
 *   GET /api/ai/health → {"gemini":"UP","deepseek":"UP","qwen":"UP","primary":"deepseek"}
 *   GET /api/ai/stats  → {"deepseekCalls":250,"geminiCalls":48,"qwenCalls":6,"fallbackCount":54, …}
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiGatewayService gateway;
    private final ProviderHealthTracker healthTracker;

    public AiController(AiGatewayService gateway, ProviderHealthTracker healthTracker) {
        this.gateway = gateway;
        this.healthTracker = healthTracker;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return gateway.health();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return gateway.stats();
    }
}
