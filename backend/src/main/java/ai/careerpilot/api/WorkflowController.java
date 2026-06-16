package ai.careerpilot.api;

import ai.careerpilot.domain.WorkflowRun;
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
    public WorkflowRun start(AuthenticatedUser user, @RequestBody StartWorkflowRequest req) {
        return workflows.start(user.userId(), user.orgId(), req);
    }

    @PostMapping("/{threadId}/resume")
    public WorkflowRun resume(AuthenticatedUser user,
                              @PathVariable String threadId,
                              @RequestBody Map<String, String> body) {
        return workflows.resume(user.userId(), threadId,
                body.getOrDefault("decision", "approved"),
                body.getOrDefault("feedback", ""));
    }

    @GetMapping("/{threadId}")
    public WorkflowRun get(AuthenticatedUser user, @PathVariable String threadId) {
        return workflows.get(user.userId(), threadId);
    }

    @GetMapping
    public List<WorkflowRun> recent(AuthenticatedUser user) {
        return workflows.recent(user.userId());
    }
}
