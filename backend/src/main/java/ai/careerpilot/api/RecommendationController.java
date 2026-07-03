package ai.careerpilot.api;

import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJobsResponse;
import ai.careerpilot.domain.Application;
import ai.careerpilot.domain.CandidateBehaviorProfile;
import ai.careerpilot.domain.RecommendationAudit;
import ai.careerpilot.domain.RecommendationFeedback;
import ai.careerpilot.repo.RecommendationAuditRepository;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.CandidateBehaviorProfileService;
import ai.careerpilot.service.JobRecommendationService;
import ai.careerpilot.service.RecommendationDecisionService;
import ai.careerpilot.service.RecommendationFeedbackService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 2C — Recommendation Intelligence API (Step 12). Mounted at a NEW {@code /api/recommendations}
 * namespace so the existing {@code /api/jobs/*} contract is untouched (ZERO API contract changes).
 *
 * <p>The read endpoints delegate to the existing {@link JobRecommendationService} (the same data the
 * Recommended tab already serves), just pre-filtered into the 2C "collections". The decision
 * endpoints delegate to {@link RecommendationDecisionService}, which writes to the existing
 * {@code Application} entity — no parallel queue table.
 */
@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    /** Decision action → the feedback action recorded alongside it (2C-4 auto-capture). */
    private static final Map<String, String> DECISION_TO_FEEDBACK = Map.of(
            "approve", "APPROVE",
            "reject", "REJECT",
            "save", "SAVE",
            "archive", "IGNORE");

    private final JobRecommendationService recommendations;
    private final RecommendationDecisionService decisions;
    private final RecommendationFeedbackService feedback;
    private final CandidateBehaviorProfileService behaviorProfile;
    private final RecommendationAuditRepository audit;

    public RecommendationController(JobRecommendationService recommendations,
                                    RecommendationDecisionService decisions,
                                    RecommendationFeedbackService feedback,
                                    CandidateBehaviorProfileService behaviorProfile,
                                    RecommendationAuditRepository audit) {
        this.recommendations = recommendations;
        this.decisions = decisions;
        this.feedback = feedback;
        this.behaviorProfile = behaviorProfile;
        this.audit = audit;
    }

    /** GET /api/recommendations — the full Recommended list (mirrors /api/jobs/recommended). */
    @GetMapping
    public RecommendedJobsResponse list(AuthenticatedUser user,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "10") int size,
                                        @RequestParam(defaultValue = "all") String filter) {
        return recommendations.recommend(user.userId(), user.orgId(), page, size, filter);
    }

    /** GET /api/recommendations/must-apply — only the MUST_APPLY collection (Step 9). */
    @GetMapping("/must-apply")
    public RecommendedJobsResponse mustApply(AuthenticatedUser user,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "10") int size) {
        return recommendations.recommend(user.userId(), user.orgId(), page, size, "must-apply");
    }

    /** GET /api/recommendations/human-review — only the HUMAN_REVIEW collection (Step 9). */
    @GetMapping("/human-review")
    public RecommendedJobsResponse humanReview(AuthenticatedUser user,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        return recommendations.recommend(user.userId(), user.orgId(), page, size, "human-review");
    }

    /** GET /api/recommendations/audit — this user's decision/scoring audit trail (Step 10). */
    @GetMapping("/audit")
    public List<RecommendationAudit> auditTrail(AuthenticatedUser user) {
        return audit.findByUserIdOrderByCreatedAtDesc(user.userId());
    }

    @PostMapping("/approve")
    public Application approve(AuthenticatedUser user, @RequestBody Map<String, String> body) {
        return decide(user, body, "approve");
    }

    @PostMapping("/reject")
    public Application reject(AuthenticatedUser user, @RequestBody Map<String, String> body) {
        return decide(user, body, "reject");
    }

    @PostMapping("/save")
    public Application save(AuthenticatedUser user, @RequestBody Map<String, String> body) {
        return decide(user, body, "save");
    }

    @PostMapping("/archive")
    public Application archive(AuthenticatedUser user, @RequestBody Map<String, String> body) {
        return decide(user, body, "archive");
    }

    /** Shared decision handler: flag-gate → validate jobId → delegate to the decision service. */
    private Application decide(AuthenticatedUser user, Map<String, String> body, String action) {
        if (!decisions.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "recommendation approval is disabled");
        }
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId is required");
        }
        String jobId = body.get("jobId");
        if (jobId == null || jobId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId is required");
        }
        UUID jid;
        try {
            jid = UUID.fromString(jobId.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId is not a valid UUID");
        }
        String reason = body.get("reason");
        Application result = decisions.decide(user.userId(), user.orgId(), jid, action, reason);
        // 2C-4 auto-capture: a decision always leaves a feedback trail (no-op when feedback flag off).
        feedback.record(user.userId(), jid, DECISION_TO_FEEDBACK.get(action), reason);
        return result;
    }

    /**
     * POST /api/recommendations/feedback — record a standalone reaction (Step 6). Body:
     * {@code {jobId, action, reason?}} where action ∈ APPROVE|REJECT|IGNORE|SAVE|APPLY_LATER.
     * 404 when feedback is disabled; 400 on a missing/invalid jobId or unknown action.
     */
    @PostMapping("/feedback")
    public RecommendationFeedback submitFeedback(AuthenticatedUser user, @RequestBody Map<String, String> body) {
        if (!feedback.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "recommendation feedback is disabled");
        }
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId and action are required");
        }
        String jobId = body.get("jobId");
        String action = body.get("action");
        if (jobId == null || jobId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId is required");
        }
        if (!RecommendationFeedbackService.isValidAction(action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "action must be one of APPROVE, REJECT, IGNORE, SAVE, APPLY_LATER");
        }
        UUID jid;
        try {
            jid = UUID.fromString(jobId.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId is not a valid UUID");
        }
        return feedback.record(user.userId(), jid, action, body.get("reason"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "recommendation feedback is disabled"));
    }

    /** GET /api/recommendations/feedback — this user's feedback history (drives the behavior profile). */
    @GetMapping("/feedback")
    public List<RecommendationFeedback> feedbackHistory(AuthenticatedUser user) {
        return feedback.listForUser(user.userId());
    }

    /**
     * GET /api/recommendations/behavior-profile — this user's inferred behavior profile (Step 7).
     * 404 when none has been built yet (the UI should treat that as "not available", not an error).
     */
    @GetMapping("/behavior-profile")
    public CandidateBehaviorProfile behaviorProfile(AuthenticatedUser user) {
        return behaviorProfile.get(user.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no behavior profile yet"));
    }

    /**
     * POST /api/recommendations/behavior-profile/rebuild — recompute the behavior profile from the
     * user's feedback history. 404 when feedback (and thus the profile) is disabled.
     */
    @PostMapping("/behavior-profile/rebuild")
    public CandidateBehaviorProfile rebuildBehaviorProfile(AuthenticatedUser user) {
        if (!behaviorProfile.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "recommendation feedback is disabled");
        }
        return behaviorProfile.rebuild(user.userId());
    }
}
