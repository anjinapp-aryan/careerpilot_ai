package ai.careerpilot.careertimeline.api;

import ai.careerpilot.careertimeline.CareerTimelineCategory;
import ai.careerpilot.careertimeline.CareerTimelineEntry;
import ai.careerpilot.careertimeline.CareerTimelineService;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Phase 10H — the single unified Career Timeline read surface. JWT-authenticated (own-data only,
 * same manual per-user scoping convention as every other controller in this codebase — no new
 * isolation mechanism introduced).
 */
@RestController
@RequestMapping("/api/career-timeline")
public class CareerTimelineController {

    private final CareerTimelineService service;

    public CareerTimelineController(CareerTimelineService service) {
        this.service = service;
    }

    public record CareerTimelineResponse(List<CareerTimelineEntry> entries, boolean hasMore, boolean enabled) {
    }

    @GetMapping
    public CareerTimelineResponse get(
            AuthenticatedUser user,
            @RequestParam(required = false) CareerTimelineCategory category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        CareerTimelineService.Page result = service.forUser(user.userId(), category, page, size);
        return new CareerTimelineResponse(result.entries(), result.hasMore(), service.isEnabled());
    }
}
