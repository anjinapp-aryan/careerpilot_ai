package ai.careerpilot.api;

import ai.careerpilot.api.dto.MissionDtos.MissionRequest;
import ai.careerpilot.api.dto.MissionDtos.MissionResponse;
import ai.careerpilot.mission.MissionService;
import ai.careerpilot.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Mission Engine, Phase 1 — {@code POST/GET/PUT/DELETE /api/missions[/{id}]}. {@code GET
 * /api/missions} (list, no id) is a small, non-spec addition for basic usability (a user needs to
 * see their own missions) — everything else matches the requested contract exactly.
 */
@RestController
@RequestMapping("/api/missions")
public class MissionController {

    private final MissionService service;

    public MissionController(MissionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MissionResponse create(AuthenticatedUser user, @Valid @RequestBody MissionRequest request) {
        return MissionResponse.from(service.create(user.userId(), request));
    }

    @GetMapping
    public List<MissionResponse> list(AuthenticatedUser user) {
        return service.list(user.userId()).stream().map(MissionResponse::from).toList();
    }

    @GetMapping("/{id}")
    public MissionResponse get(AuthenticatedUser user, @PathVariable UUID id) {
        return MissionResponse.from(service.get(user.userId(), id));
    }

    @PutMapping("/{id}")
    public MissionResponse update(AuthenticatedUser user, @PathVariable UUID id,
                                   @Valid @RequestBody MissionRequest request) {
        return MissionResponse.from(service.update(user.userId(), id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(AuthenticatedUser user, @PathVariable UUID id) {
        service.delete(user.userId(), id);
    }
}
