package ai.careerpilot.api;

import ai.careerpilot.applications.ApplicationCardService;
import ai.careerpilot.applications.dto.ApplicationCardDtos.ApplicationCardResponse;
import ai.careerpilot.applications.dto.ApplicationCardDtos.BulkActionRequest;
import ai.careerpilot.applications.dto.ApplicationCardDtos.BulkActionResult;
import ai.careerpilot.domain.Application;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.ApplicationService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {

    private final ApplicationService apps;
    private final ApplicationCardService cards;

    public ApplicationController(ApplicationService apps, ApplicationCardService cards) {
        this.apps = apps;
        this.cards = cards;
    }

    @PostMapping
    public Application create(AuthenticatedUser user, @RequestBody Application body) {
        return apps.create(user.userId(), user.orgId(), body);
    }

    /** Unchanged contract — existing consumers (Jobs.tsx, Applications.tsx drag-drop) keep working byte-for-byte. */
    @GetMapping
    public List<Application> list(AuthenticatedUser user) {
        return apps.listForUser(user.userId());
    }

    /**
     * Application Command Center — new, additive endpoint. Same rows as {@link #list}, but each is
     * enriched with joined job/artifact data plus deterministic health/recommendation/next-action.
     */
    @GetMapping("/cards")
    public List<ApplicationCardResponse> listCards(AuthenticatedUser user) {
        List<Application> applications = apps.listForUser(user.userId());
        return cards.assembleAll(user.userId(), applications);
    }

    /** New — single-application fetch, needed by the drawer. Didn't exist before this feature. */
    @GetMapping("/{id}")
    public ApplicationCardResponse get(AuthenticatedUser user, @PathVariable UUID id) {
        Application app = apps.getOwned(user.userId(), id);
        return cards.assemble(user.userId(), app);
    }

    @PatchMapping("/{id}")
    public Application patch(AuthenticatedUser user, @PathVariable UUID id, @RequestBody Map<String, String> body) {
        Boolean favorite = body.containsKey("favorite") ? Boolean.valueOf(body.get("favorite")) : null;
        Boolean archived = body.containsKey("archived") ? Boolean.valueOf(body.get("archived")) : null;
        return apps.updateFields(user.userId(), id, body.get("status"), body.get("notes"), favorite, body.get("priority"), archived);
    }

    /**
     * New — bulk action endpoint. {@code action} is one of STATUS|ARCHIVE|NOTES|NEXT_ACTION|RESUME|EXPORT.
     * "RESUME" only reassigns {@code resumeId} — never triggers bulk AI regeneration. "EXPORT" is a
     * pure read (no mutation); it returns the requested ids as "applied" once ownership is confirmed,
     * leaving the actual CSV/JSON rendering to the frontend, which already has the card data client-side.
     */
    @PostMapping("/bulk")
    public BulkActionResult bulk(AuthenticatedUser user, @RequestBody BulkActionRequest request) {
        List<UUID> ids = request.ids() == null ? List.of() : request.ids();
        String action = request.action() == null ? "" : request.action().toUpperCase();
        Map<String, String> payload = request.payload() == null ? Map.of() : request.payload();

        List<Application> owned = apps.listForUser(user.userId()).stream()
                .filter(a -> ids.contains(a.getId()))
                .toList();
        List<UUID> failed = new ArrayList<>(ids);
        owned.forEach(a -> failed.remove(a.getId()));

        boolean knownAction = isKnownAction(action);
        int applied = 0;
        for (Application a : owned) {
            if (!knownAction) {
                failed.add(a.getId());
                continue;
            }
            switch (action) {
                case "STATUS" -> apps.updateFields(user.userId(), a.getId(), payload.get("status"), null, null, null, null);
                case "ARCHIVE" -> apps.updateFields(user.userId(), a.getId(), null, null, null, null, Boolean.TRUE);
                case "NOTES" -> apps.updateFields(user.userId(), a.getId(), null, payload.get("notes"), null, null, null);
                case "NEXT_ACTION" -> apps.updateNextAction(user.userId(), a.getId(), payload.get("nextAction"),
                        payload.get("nextActionAt") == null ? null : Instant.parse(payload.get("nextActionAt")));
                case "RESUME" -> apps.reassignResume(user.userId(), a.getId(),
                        payload.get("resumeId") == null ? null : UUID.fromString(payload.get("resumeId")));
                case "EXPORT" -> { /* no-op mutation; ids already confirmed owned */ }
                default -> { failed.add(a.getId()); continue; }
            }
            applied++;
        }

        return new BulkActionResult(ids.size(), applied, failed);
    }

    private boolean isKnownAction(String action) {
        return List.of("STATUS", "ARCHIVE", "NOTES", "NEXT_ACTION", "RESUME", "EXPORT").contains(action);
    }
}
