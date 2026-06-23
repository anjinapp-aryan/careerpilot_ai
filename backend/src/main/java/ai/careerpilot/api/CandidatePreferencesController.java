package ai.careerpilot.api;

import ai.careerpilot.api.dto.CandidatePreferencesDto;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.CandidatePreferencesService;
import org.springframework.web.bind.annotation.*;

/**
 * Per-user job preferences. User-scoped via the JWT principal; the preference row is keyed
 * by user id so there is no cross-tenant exposure.
 */
@RestController
@RequestMapping("/api/candidate/preferences")
public class CandidatePreferencesController {

    private final CandidatePreferencesService service;

    public CandidatePreferencesController(CandidatePreferencesService service) {
        this.service = service;
    }

    @GetMapping
    public CandidatePreferencesDto get(AuthenticatedUser user) {
        return service.get(user.userId());
    }

    @PutMapping
    public CandidatePreferencesDto update(AuthenticatedUser user, @RequestBody CandidatePreferencesDto body) {
        return service.save(user.userId(), body);
    }
}
