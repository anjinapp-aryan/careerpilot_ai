package ai.careerpilot.api;

import ai.careerpilot.api.dto.MissionOrchestratorDtos.MissionExecutionResponse;
import ai.careerpilot.mission.MissionOrchestratorService;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.*;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Mission Orchestrator, Phase 5 — {@code POST /api/orchestrator/run/{missionId}}, {@code GET
 * /api/orchestrator/status/{missionId}}.
 */
@RestController
@RequestMapping("/api/orchestrator")
public class MissionOrchestratorController {

    private final MissionOrchestratorService orchestrator;

    public MissionOrchestratorController(MissionOrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/run/{missionId}")
    public MissionExecutionResponse run(AuthenticatedUser user, @PathVariable UUID missionId) {
        return MissionExecutionResponse.from(orchestrator.run(user.userId(), missionId));
    }

    @GetMapping("/status/{missionId}")
    public MissionExecutionResponse status(AuthenticatedUser user, @PathVariable UUID missionId) {
        return orchestrator.status(user.userId(), missionId)
                .map(MissionExecutionResponse::from)
                .orElseThrow(() -> new NoSuchElementException("Orchestrator has not run yet for mission: " + missionId));
    }
}
