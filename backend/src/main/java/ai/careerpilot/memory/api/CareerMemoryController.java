package ai.careerpilot.memory.api;

import ai.careerpilot.domain.CareerDecisionMemory;
import ai.careerpilot.memory.CareerMemoryService;
import ai.careerpilot.memory.CareerMemoryService.MemorySummary;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Phase 7.15.1/7.15.3 — Career Decision Memory. Per-user data (a person's remembered decisions),
 * so this goes through the normal JWT-authenticated path, same convention as
 * {@code OfferController} — NOT added to SecurityConfig's permitAll list. Ships dark: degrades
 * to an empty list/zeroed summary, not a 404 or error, when {@code career.memory.enabled} is off
 * — same "dark-flag endpoints degrade gracefully" convention as {@code OfferController}.
 *
 * <p>Every write here goes through {@link CareerMemoryService} — no controller ever touches
 * {@code CareerDecisionMemoryRepository} directly, and neither approve/confirm nor forget is a
 * hard delete (see {@link CareerMemoryService#forget}): the append-only design is preserved.
 */
@RestController
@RequestMapping("/api/career-memory")
public class CareerMemoryController {

    private final CareerMemoryService careerMemory;
    private final boolean enabled;

    public CareerMemoryController(CareerMemoryService careerMemory,
                                  @Value("${career.memory.enabled:false}") boolean enabled) {
        this.careerMemory = careerMemory;
        this.enabled = enabled;
    }

    /**
     * Full chronological career timeline (review area 6) — newest first, not ranked/truncated
     * like the Copilot's retrieval path. Empty list when disabled.
     */
    @GetMapping("/timeline")
    public List<CareerDecisionMemory> timeline(AuthenticatedUser user) {
        if (!enabled) return List.of();
        return careerMemory.timelineFor(user.userId());
    }

    /**
     * Active memories, optionally narrowed to one category (for the dashboard's per-category
     * cards) — the same ranked-eligible pool {@code CareerMemoryBooster}/Copilot retrieval read
     * from, not the full unbounded ledger. Empty list when disabled or no {@code category} match.
     */
    @GetMapping
    public List<CareerDecisionMemory> list(AuthenticatedUser user, @RequestParam(required = false) String category) {
        if (!enabled) return List.of();
        if (category == null || category.isBlank()) return careerMemory.relevantFor(user.userId(), 200);
        return careerMemory.forCategory(user.userId(), category.toUpperCase());
    }

    /** The "AI Learned About You" header stats. Zeroed (not an error) when disabled. */
    @GetMapping("/summary")
    public MemorySummary summary(AuthenticatedUser user) {
        return careerMemory.summaryFor(user.userId());
    }

    public record EditMemoryRequest(String category, String value, String reason) {}

    /**
     * "Edit Memory" — the user directly tells the AI a preference (country, salary, technology,
     * career goal, etc.). Writes a NEW row via {@link CareerMemoryService#recordUserEdit} — never
     * mutates an existing one; append-only history is preserved. 409 when disabled, 400 when
     * category/value are missing.
     */
    @PostMapping
    public ResponseEntity<CareerDecisionMemory> edit(AuthenticatedUser user, @RequestBody EditMemoryRequest req) {
        if (!enabled) return ResponseEntity.status(409).build();
        if (req.category() == null || req.category().isBlank() || req.value() == null || req.value().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return careerMemory.recordUserEdit(user.userId(), req.category(), req.value(), req.reason())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(409).build());
    }

    /** "Approve Memory" — marks a memory as user-verified. 404 when not found/not owned/disabled. */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<CareerDecisionMemory> confirm(AuthenticatedUser user, @PathVariable UUID id) {
        return careerMemory.confirm(user.userId(), id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * "Forget Memory" / "Reject" — soft-expires the memory (never deletes it; see
     * {@link CareerMemoryService#forget}). 404 when not found/not owned/disabled.
     */
    @PostMapping("/{id}/forget")
    public ResponseEntity<CareerDecisionMemory> forget(AuthenticatedUser user, @PathVariable UUID id) {
        return careerMemory.forget(user.userId(), id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
