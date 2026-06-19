package ai.careerpilot.api;

import ai.careerpilot.ai.AiGatewayService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Operational endpoints for the AI Gateway.
 *
 *   GET /api/ai/health → {"gemini":"UP","deepseek":"UP","qwen":"UP","primary":"gemini"}
 *   GET /api/ai/stats  → {"geminiCalls":1250,"deepseekCalls":48,"qwenCalls":6,"fallbackCount":54, …}
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
}
