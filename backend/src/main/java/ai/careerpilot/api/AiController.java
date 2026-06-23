package ai.careerpilot.api;

import ai.careerpilot.ai.AiGatewayService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Operational endpoints for the AI Gateway.
 *
 *   GET /api/ai/health           → {"gemini":"UP","deepseek":"UP","qwen":"UP","primary":"deepseek"}
 *   GET /api/ai/stats            → {"deepseekCalls":250,"geminiCalls":48,"fallbackCount":54, …}
 *   GET /api/ai/providers        → [{"name":"deepseek","status":"UP","model":…}, …] (failover order)
 *   GET /api/ai/providers/health → {"deepseek":"UP","gemini":"UP","glm":"NOT_CONFIGURED", …}
 *   GET /api/ai/router/status    → {"smartRouterEnabled":false,"mode":"sequential","order":[…]}
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiGatewayService gateway;

    public AiController(AiGatewayService gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return gateway.health();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return gateway.stats();
    }

    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        return gateway.providerStatuses();
    }

    @GetMapping("/providers/health")
    public Map<String, String> providersHealth() {
        return gateway.health();
    }

    @GetMapping("/router/status")
    public Map<String, Object> routerStatus() {
        return gateway.routerStatus();
    }
}
