package ai.careerpilot.api;

import ai.careerpilot.api.dto.WorkflowRegistryDtos.ExecuteWorkflowRequest;
import ai.careerpilot.api.dto.WorkflowRegistryDtos.WorkflowDefinitionRequest;
import ai.careerpilot.api.dto.WorkflowRegistryDtos.WorkflowDefinitionResponse;
import ai.careerpilot.api.dto.WorkflowRegistryDtos.WorkflowExecutionResponse;
import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.workflowregistry.WorkflowExecutionService;
import ai.careerpilot.workflowregistry.WorkflowRegistryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Workflow Registry, Phase 4 — {@code GET /api/workflow-definitions}, {@code POST
 * /api/workflow/register}, {@code POST /api/workflow/{id}/execute}. A catalog, not an execution
 * engine — see {@code WorkflowExecutionService}'s javadoc for exactly what {@code execute} does
 * today.
 *
 * <p>The phase spec's literal {@code GET /workflows} path was already taken by the existing
 * {@code WorkflowController} (LangGraph {@code WorkflowRun} history, Phase 3A — a completely
 * different, pre-existing concept) — using it here would have silently shadowed that endpoint.
 * {@code /api/workflow-definitions} is the collision-free equivalent; {@code /api/workflow/*}
 * (singular) never collided since the existing controller is exclusively plural.
 */
@RestController
@RequestMapping("/api")
public class WorkflowRegistryController {

    private final WorkflowRegistryService registry;
    private final WorkflowExecutionService executions;

    public WorkflowRegistryController(WorkflowRegistryService registry, WorkflowExecutionService executions) {
        this.registry = registry;
        this.executions = executions;
    }

    @GetMapping("/workflow-definitions")
    public List<WorkflowDefinitionResponse> list(AuthenticatedUser user) {
        return registry.listActive().stream().map(WorkflowDefinitionResponse::from).toList();
    }

    @PostMapping("/workflow/register")
    public WorkflowDefinitionResponse register(AuthenticatedUser user, @Valid @RequestBody WorkflowDefinitionRequest request) {
        return WorkflowDefinitionResponse.from(registry.register(request.toEntity()));
    }

    @PostMapping("/workflow/{id}/execute")
    public WorkflowExecutionResponse execute(AuthenticatedUser user, @PathVariable("id") String workflowId,
                                              @RequestBody(required = false) ExecuteWorkflowRequest request) {
        WorkflowDefinition definition = registry.get(workflowId);
        UUID missionId = request != null ? request.missionId() : null;
        return WorkflowExecutionResponse.from(executions.execute(definition, user.userId(), missionId), workflowId);
    }
}
