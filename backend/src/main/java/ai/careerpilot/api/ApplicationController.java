package ai.careerpilot.api;

import ai.careerpilot.domain.Application;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.ApplicationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationService apps;

    public ApplicationController(ApplicationService apps) { this.apps = apps; }

    @PostMapping
    public Application create(AuthenticatedUser user, @RequestBody Application body) {
        return apps.create(user.userId(), user.orgId(), body);
    }

    @GetMapping
    public List<Application> list(AuthenticatedUser user) {
        return apps.listForUser(user.userId());
    }

    @PatchMapping("/{id}")
    public Application patch(AuthenticatedUser user, @PathVariable UUID id, @RequestBody Map<String, String> body) {
        return apps.updateStatus(user.userId(), id, body.get("status"), body.get("notes"));
    }
}
