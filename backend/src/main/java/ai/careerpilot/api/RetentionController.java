package ai.careerpilot.api;

import ai.careerpilot.retention.RetentionService;
import ai.careerpilot.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Admin surface for the flag-gated data-retention job. Same manual admin-role gate as
 * {@link AdminStatsController} (the project uses no {@code @PreAuthorize}); non-admin → 403. The purge
 * itself is additionally gated by {@code retention.enabled}: when disabled, {@code POST /run} returns a
 * {@code disabled} marker and deletes nothing, so this endpoint is safe to expose while retention ships
 * dark.
 */
@RestController
@RequestMapping("/api/admin/retention")
public class RetentionController {

    private static final Logger log = LoggerFactory.getLogger(RetentionController.class);
    private static final Set<String> ADMIN_ROLES = Set.of("OWNER", "ADMIN");

    private final RetentionService retention;

    public RetentionController(RetentionService retention) {
        this.retention = retention;
    }

    /** Report whether retention is enabled (does not run anything). */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(AuthenticatedUser user) {
        if (denied(user)) return ResponseEntity.status(403).build();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", retention.isEnabled());
        return ResponseEntity.ok(out);
    }

    /** Manually trigger a purge now. No-op (returns {@code enabled:false}) when retention is disabled. */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(AuthenticatedUser user) {
        if (denied(user)) return ResponseEntity.status(403).build();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", retention.isEnabled());
        out.put("purged", retention.purgeAll());
        return ResponseEntity.ok(out);
    }

    private boolean denied(AuthenticatedUser user) {
        boolean ok = user != null && ADMIN_ROLES.contains(user.role());
        if (!ok) log.warn("RETENTION admin denied — user={} role={}",
                user != null ? user.userId() : null, user != null ? user.role() : null);
        return !ok;
    }
}
