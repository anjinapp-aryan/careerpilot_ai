package ai.careerpilot.api;

import ai.careerpilot.api.dto.WorkflowDtos.WorkflowRunResponse;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.WorkflowService;
import ai.careerpilot.service.WorkflowService.StartWorkflowRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowService workflows;

    public WorkflowController(WorkflowService workflows) { this.workflows = workflows; }

    @PostMapping("/run")
    public WorkflowRunResponse start(AuthenticatedUser user, @RequestBody StartWorkflowRequest req) {
        return workflows.toResponse(workflows.start(user.userId(), user.orgId(), req));
    }

    @PostMapping("/{threadId}/resume")
    public WorkflowRunResponse resume(AuthenticatedUser user,
                                      @PathVariable String threadId,
                                      @RequestBody Map<String, String> body) {
        return workflows.toResponse(workflows.resume(user.userId(), threadId,
                body.getOrDefault("decision", "approved"),
                body.getOrDefault("feedback", "")));
    }

    @GetMapping("/{threadId}")
    public WorkflowRunResponse get(AuthenticatedUser user, @PathVariable String threadId) {
        return workflows.toResponse(workflows.get(user.userId(), threadId));
    }

    @GetMapping
    public List<WorkflowRunResponse> recent(AuthenticatedUser user) {
        return workflows.toResponseList(workflows.recent(user.userId()));
    }
}
