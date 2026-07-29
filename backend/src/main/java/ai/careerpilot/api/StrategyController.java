package ai.careerpilot.api;

import ai.careerpilot.api.dto.StrategyDtos.GenerateStrategyRequest;
import ai.careerpilot.api.dto.StrategyDtos.StrategyActionResponse;
import ai.careerpilot.api.dto.StrategyDtos.StrategyPlanResponse;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.strategy.StrategyEvaluationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Strategy Engine, Phase 3 — {@code POST /api/strategy/generate}, {@code GET
 * /api/strategy/{missionId}}, {@code PUT /api/strategy/action/{id}/complete}. Every mutation is
 * ownership-scoped by the authenticated user, same convention as {@code MissionController}.
 */
@RestController
@RequestMapping("/api/strategy")
public class StrategyController {

    private final StrategyEvaluationService service;

    public StrategyController(StrategyEvaluationService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    public StrategyPlanResponse generate(AuthenticatedUser user, @Valid @RequestBody GenerateStrategyRequest request) {
        return StrategyPlanResponse.from(service.generate(user.userId(), request.missionId()));
    }

    @GetMapping("/{missionId}")
    public StrategyPlanResponse latest(AuthenticatedUser user, @PathVariable UUID missionId) {
        return StrategyPlanResponse.from(service.latest(user.userId(), missionId));
    }

    @PutMapping("/action/{id}/complete")
    public StrategyActionResponse completeAction(AuthenticatedUser user, @PathVariable UUID id) {
        return StrategyActionResponse.from(service.completeAction(user.userId(), id));
    }
}
