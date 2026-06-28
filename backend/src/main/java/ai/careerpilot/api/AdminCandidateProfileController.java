package ai.careerpilot.api;

import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.profile.CandidateProfileBackfillService;
import ai.careerpilot.service.profile.CandidateProfileBackfillService.BackfillReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/**
 * Ops-only surface for the Candidate Intelligence Profile. Separate from the user-facing
 * {@link CandidateProfileController} because backfill operates across all users, not the caller's
 * own row.
 *
 * <p>Two guards, both required: the feature flag ({@code CANDIDATE_PROFILE_ENABLED}) and an explicit
 * admin-role check (the project has {@code @EnableMethodSecurity} on but uses no {@code @PreAuthorize};
 * this controller enforces the role manually, the same way tenancy is enforced manually elsewhere).
 * Disabled flag → 404 (consistent with the user controller); non-admin → 403.
 */
@RestController
@RequestMapping("/api/admin/candidate-profile")
public class AdminCandidateProfileController {

    private static final Logger log = LoggerFactory.getLogger(AdminCandidateProfileController.class);
    private static final Set<String> ADMIN_ROLES = Set.of("OWNER", "ADMIN");

    private final CandidateProfileBackfillService backfill;
    private final boolean enabled;

    public AdminCandidateProfileController(CandidateProfileBackfillService backfill,
                                           @Value("${candidate.profile.enabled:false}") boolean enabled) {
        this.backfill = backfill;
        this.enabled = enabled;
    }

    /**
     * Backfill canonical profiles for existing users. Idempotent and safe to re-run.
     * Default {@code dryRun=true} so an accidental call never spends LLM budget — pass
     * {@code dryRun=false} to actually generate.
     */
    @PostMapping("/backfill")
    public ResponseEntity<BackfillReport> backfill(AuthenticatedUser user,
                                                   @RequestParam(defaultValue = "true") boolean dryRun) {
        if (!enabled) return ResponseEntity.notFound().build();
        if (user == null || !ADMIN_ROLES.contains(user.role())) {
            return ResponseEntity.status(403).build();
        }
        log.info("PROFILE_BACKFILL requested by user={} role={} dryRun={}", user.userId(), user.role(), dryRun);
        return ResponseEntity.ok(backfill.run(dryRun));
    }
}
