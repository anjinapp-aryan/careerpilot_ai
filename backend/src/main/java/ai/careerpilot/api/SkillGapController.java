package ai.careerpilot.api;

import ai.careerpilot.api.dto.SkillGapDtos.SkillGapAnalysisResponse;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.skillgap.SkillGapWorkflowService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Phase 10 — Skill Gap Intelligence Workflow, the first business workflow built on top of the
 * frozen Phase 9 platform. This controller is the entire REST surface: run, latest result,
 * history. It calls only {@link SkillGapWorkflowService} — no AI reasoning, no direct call to any
 * AI Execution Plane client, matching the Java Control Plane's "workflow registration, input
 * validation, workflow definition, API endpoint, persistence, result retrieval, dashboard
 * integration, history tracking" scope exactly.
 */
@RestController
@RequestMapping("/api/skill-gap")
public class SkillGapController {

    private final SkillGapWorkflowService service;

    public SkillGapController(SkillGapWorkflowService service) {
        this.service = service;
    }

    @PostMapping("/{missionId}/run")
    public SkillGapAnalysisResponse run(AuthenticatedUser user, @PathVariable UUID missionId) {
        return service.trigger(user.userId(), missionId);
    }

    @GetMapping("/{missionId}/latest")
    public SkillGapAnalysisResponse latest(AuthenticatedUser user, @PathVariable UUID missionId) {
        return service.latest(user.userId(), missionId);
    }

    @GetMapping("/{missionId}/history")
    public List<SkillGapAnalysisResponse> history(AuthenticatedUser user, @PathVariable UUID missionId) {
        return service.history(user.userId(), missionId);
    }
}
