package ai.careerpilot.api;

import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) { this.dashboard = dashboard; }

    @GetMapping
    public Map<String, Object> snapshot(AuthenticatedUser user) {
        return dashboard.snapshot(user.userId());
    }
}
