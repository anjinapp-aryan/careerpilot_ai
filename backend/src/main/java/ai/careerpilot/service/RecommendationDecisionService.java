package ai.careerpilot.service;

import ai.careerpilot.domain.Application;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.repo.ApplicationRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.RecommendationAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 2C-3 (Steps 5 + 12) — the Human Approval Queue actions. Per the "reuse, don't duplicate"
 * decision, an approve/reject/save/archive on a recommendation writes to the existing
 * {@link Application} entity (the single source of truth for "what did the user do with this job",
 * already backing the live Applications Kanban) rather than a parallel {@code recommendation_queue}
 * table.
 *
 * <p>Action → Application status mapping (chosen to respect the Kanban, which only renders
 * SAVED/APPLIED/INTERVIEWING/OFFER/REJECTED and silently hides any other status):
 * <ul>
 *   <li>{@code approve} → {@code SAVED} — enters the pipeline, visible. Auto-apply is out of scope,
 *       so approval never marks APPLIED; the "approved" nuance is recorded in the audit decision.</li>
 *   <li>{@code save} → {@code SAVED} — same as the existing Save button.</li>
 *   <li>{@code reject} → {@code REJECTED} — visible in the Rejected column.</li>
 *   <li>{@code archive} → {@code ARCHIVED} — a new, intentionally board-invisible status = dismissed.</li>
 * </ul>
 *
 * <p>Best-effort second write: if a scoring-audit row exists for this (user, job), the decision is
 * stamped onto its latest row so recommendation decisions are replayable (Step 10) — reusing V15's
 * {@code recommendation_audit} rather than a new {@code recommendation_decision_audit} table.
 *
 * <p>Flag-gated by {@code recommendation.approval.enabled} (default off). Idempotent: repeating an
 * action just re-sets the same status.
 */
@Service
public class RecommendationDecisionService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationDecisionService.class);

    /** Action → Application status. Keys are the canonical lower-case action names. */
    private static final Map<String, String> ACTION_TO_STATUS = Map.of(
            "approve", "SAVED",
            "save", "SAVED",
            "reject", "REJECTED",
            "archive", "ARCHIVED");

    private final ApplicationRepository apps;
    private final JobRecommendationRepository recommendations;
    private final RecommendationAuditRepository audit;
    private final boolean enabled;

    public RecommendationDecisionService(ApplicationRepository apps,
                                         JobRecommendationRepository recommendations,
                                         RecommendationAuditRepository audit,
                                         @Value("${recommendation.approval.enabled:false}") boolean enabled) {
        this.apps = apps;
        this.recommendations = recommendations;
        this.audit = audit;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Whether {@code action} is one this service understands. */
    public static boolean isValidAction(String action) {
        return action != null && ACTION_TO_STATUS.containsKey(action.trim().toLowerCase());
    }

    /**
     * Apply a decision. Upserts the {@link Application} for (user, job) and best-effort stamps the
     * decision onto the latest scoring-audit row. Throws {@link IllegalArgumentException} on an
     * unknown action (the controller maps that to 400).
     */
    @Transactional
    public Application decide(UUID userId, UUID orgId, UUID jobId, String action, String reason) {
        String normalized = action == null ? "" : action.trim().toLowerCase();
        String status = ACTION_TO_STATUS.get(normalized);
        if (status == null) {
            throw new IllegalArgumentException("unknown action: " + action);
        }

        Application app = apps.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId).orElse(null);
        if (app == null) {
            Integer score = recommendations.findByUserIdAndJobId(userId, jobId)
                    .map(JobRecommendation::getMatchScore).orElse(null);
            app = Application.builder()
                    .userId(userId).orgId(orgId).jobId(jobId)
                    .status(status).matchScore(score).notes(reason)
                    .build();
        } else {
            app.setStatus(status);
            if (reason != null) app.setNotes(reason);
        }
        Application saved = apps.save(app);

        // Best-effort replay stamp — only when a scoring-audit row exists (audit flag was on at match time).
        audit.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId).ifPresent(row -> {
            row.setDecision(normalized.toUpperCase());
            audit.save(row);
        });

        log.info("RECO_DECISION user={} job={} action={} status={}", userId, jobId, normalized, status);
        return saved;
    }
}
