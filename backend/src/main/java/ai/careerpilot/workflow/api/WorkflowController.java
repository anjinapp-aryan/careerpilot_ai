package ai.careerpilot.workflow.api;

import ai.careerpilot.domain.ApplicationAnalytics;
import ai.careerpilot.domain.ApplicationLifecycle;
import ai.careerpilot.domain.ApplicationStatusHistory;
import ai.careerpilot.domain.ApplicationTimeline;
import ai.careerpilot.domain.CareerIntelligence;
import ai.careerpilot.domain.CareerLearning;
import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.domain.Interview;
import ai.careerpilot.learning.career.CareerLearningFacade;
import ai.careerpilot.learning.career.executive.ExecutiveDecisionEngine;
import ai.careerpilot.learning.career.goal.CareerGoalEngine;
import ai.careerpilot.learning.career.goal.CareerGoalPlannerService;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.workflow.analytics.ApplicationAnalyticsService;
import ai.careerpilot.workflow.career.CareerIntelligenceService;
import ai.careerpilot.workflow.entry.WorkflowEntryBridge;
import ai.careerpilot.workflow.interview.InterviewService;
import ai.careerpilot.workflow.timeline.TimelineService;
import ai.careerpilot.workflow.tracking.ApplicationLifecycleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 3A — the human-facing read/seed surface for the application-tracking workflow, distinct from the
 * no-auth diagnostics. Multi-tenant isolation is enforced manually: every read is scoped to
 * {@code user.userId()} (repo convention — no row-level security), exactly like the rest of the app.
 *
 * <p>All engines ship DARK: with stock flags every read returns an empty/absent result and the gated
 * {@code POST} mutations are no-ops (the underlying service short-circuits when its flag is off), so this
 * controller is inert until the operator canaries a stage on.
 *
 * <p>Explicitly named {@code workflowLifecycleController} (not the default {@code workflowController}) —
 * it would otherwise collide, at full component-scan boot, with {@link ai.careerpilot.api.WorkflowController}
 * (the pre-existing LangGraph run/resume/get/recent controller at {@code /api/workflows}), which also
 * defaults to bean name {@code workflowController}. Same collision-avoidance convention already applied
 * to {@code careerAnalyticsWorker}; the original controller is left untouched.
 */
@RestController("workflowLifecycleController")
@RequestMapping("/api/workflow")
public class WorkflowController {

    private final ApplicationLifecycleService lifecycle;
    private final TimelineService timeline;
    private final InterviewService interviews;
    private final ApplicationAnalyticsService analytics;
    private final CareerIntelligenceService career;
    private final WorkflowEntryBridge entry;
    private final CareerLearningFacade careerLearning;
    private final CareerGoalEngine careerGoalEngine;
    private final CareerGoalPlannerService careerGoalPlanner;
    private final ExecutiveDecisionEngine executiveDecisionEngine;

    public WorkflowController(ApplicationLifecycleService lifecycle, TimelineService timeline,
                              InterviewService interviews, ApplicationAnalyticsService analytics,
                              CareerIntelligenceService career, WorkflowEntryBridge entry,
                              CareerLearningFacade careerLearning, CareerGoalEngine careerGoalEngine,
                              CareerGoalPlannerService careerGoalPlanner, ExecutiveDecisionEngine executiveDecisionEngine) {
        this.lifecycle = lifecycle;
        this.timeline = timeline;
        this.interviews = interviews;
        this.analytics = analytics;
        this.career = career;
        this.entry = entry;
        this.careerLearning = careerLearning;
        this.careerGoalEngine = careerGoalEngine;
        this.careerGoalPlanner = careerGoalPlanner;
        this.executiveDecisionEngine = executiveDecisionEngine;
    }

    /** Lifecycle row + append-only status history for one (user, job). 404 when no lifecycle exists. */
    @GetMapping("/applications/{jobId}/lifecycle")
    public ResponseEntity<LifecycleView> lifecycle(AuthenticatedUser user, @PathVariable UUID jobId) {
        return lifecycle.find(user.userId(), jobId)
                .map(row -> ResponseEntity.ok(new LifecycleView(row, lifecycle.statusHistory(row.getId()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Human-readable timeline for one (user, job), newest first. */
    @GetMapping("/applications/{jobId}/timeline")
    public List<ApplicationTimeline> timeline(AuthenticatedUser user, @PathVariable UUID jobId) {
        return timeline.forJob(user.userId(), jobId);
    }

    /** Interview rounds for a job (or, ungated, an empty list). */
    @GetMapping("/interviews")
    public List<Interview> interviews(AuthenticatedUser user, @RequestParam UUID jobId) {
        return interviews.forJob(user.userId(), jobId);
    }

    /** Latest outcome-analytics snapshots for the current user. */
    @GetMapping("/analytics")
    public List<ApplicationAnalytics> analytics(AuthenticatedUser user) {
        return analytics.forUser(user.userId());
    }

    /** Learned career-intelligence probabilities for the current user. */
    @GetMapping("/career-intelligence")
    public List<CareerIntelligence> careerIntelligence(AuthenticatedUser user) {
        return career.forUser(user.userId());
    }

    /**
     * Phase 6.5 — the learned career strategy (probabilities + recommended trajectory) plus top
     * learned companies/skills/industries/locations/salary bands, additive alongside the existing
     * {@code career-intelligence} endpoint above. Empty/absent fields when
     * {@code learning.adaptive-career.enabled} is off (the dark default).
     */
    @GetMapping("/career-learning")
    public CareerLearningView careerLearning(AuthenticatedUser user) {
        UUID userId = user.userId();
        return new CareerLearningView(
                careerLearning.strategy(userId).orElse(null),
                careerLearning.top(userId, CareerLearning.DIM_COMPANY),
                careerLearning.top(userId, CareerLearning.DIM_SKILL),
                careerLearning.top(userId, CareerLearning.DIM_INDUSTRY),
                careerLearning.top(userId, CareerLearning.DIM_LOCATION),
                careerLearning.top(userId, CareerLearning.DIM_SALARY));
    }

    /** Read shape for {@code GET /career-learning}. */
    public record CareerLearningView(CareerStrategy strategy, List<CareerLearning> topCompanies,
                                     List<CareerLearning> topSkills, List<CareerLearning> topIndustries,
                                     List<CareerLearning> topLocations, List<CareerLearning> topSalaryBands) {}

    /**
     * Phase 7.19 — recomputes and persists whichever of Skill Gap Intelligence / Promotion
     * Readiness / the computed Roadmap are enabled (each independently flagged), onto the SAME
     * {@code CareerStrategy} row {@code /career-learning} above reads. The raw JSON fields on the
     * returned entity are parsed client-side, same convention as the existing {@code
     * skillGapsJson}/roadmap fields.
     */
    @PostMapping("/career-goal/recompute")
    public CareerStrategy recomputeCareerGoal(AuthenticatedUser user) {
        return careerGoalEngine.recompute(user.userId());
    }

    /** The named goals {@code CareerGoalPlannerService} recognizes (e.g. "Staff Engineer", "Tech Lead"). */
    @GetMapping("/career-goal/supported-goals")
    public List<String> supportedCareerGoals() {
        return careerGoalPlanner.supportedGoals();
    }

    /** Plans one named career goal (current → target → gap → plan) and persists it onto the same row. */
    @PostMapping("/career-goal/plan")
    public Map<String, Object> planCareerGoal(AuthenticatedUser user, @RequestBody Map<String, String> body) {
        return careerGoalEngine.planGoal(user.userId(), body.get("goal"));
    }

    /**
     * Phase 7.19.5 — the Executive Decision Engine's evidence-backed decision list (Apply Now / Wait
     * Before Applying / Study Next / Prepare Interview / Switch Goal) plus career health, computed
     * live from existing intelligence modules — never a new score. Empty when
     * {@code executive.decision.enabled} is off (the dark default).
     */
    @GetMapping("/executive/decisions")
    public Map<String, Object> executiveDecisions(AuthenticatedUser user) {
        return executiveDecisionEngine.decide(user.userId());
    }

    /**
     * Gated manual status advance. Validated by the state machine; a no-op (empty) when tracking is
     * disabled or the transition is illegal. 409 when the transition is refused so the human sees it.
     */
    @PostMapping("/applications/{jobId}/status")
    public ResponseEntity<ApplicationLifecycle> advanceStatus(AuthenticatedUser user, @PathVariable UUID jobId,
                                                              @RequestBody Map<String, String> body) {
        String newStatus = body.get("status");
        String note = body.get("note");
        return lifecycle.transition(user.userId(), jobId, newStatus, note)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(409).build());
    }

    /**
     * Gated manual seed — starts the workflow for a job the human is tracking by hand. Returns the minted
     * correlation id, or 409 {@code {"status":"NOT_ENABLED"}} when {@code workflow.tracking.trigger.enabled}
     * is off (the dark default).
     */
    @PostMapping("/applications/{jobId}/seed")
    public ResponseEntity<Map<String, Object>> seed(AuthenticatedUser user, @PathVariable UUID jobId,
                                                    @RequestBody(required = false) Map<String, String> body) {
        UUID applicationId = null;
        String company = body == null ? null : body.get("company");
        String country = body == null ? null : body.get("country");
        String source = body == null ? "manual-seed" : body.getOrDefault("source", "manual-seed");
        UUID correlationId = entry.seed(user.userId(), jobId, applicationId, company, country, source);
        if (correlationId == null) {
            return ResponseEntity.status(409).body(Map.of("status", "NOT_ENABLED"));
        }
        return ResponseEntity.ok(Map.of("status", "SEEDED", "correlationId", correlationId));
    }

    /** Lifecycle row plus its status history — a small read DTO so the controller returns a stable shape. */
    public record LifecycleView(ApplicationLifecycle lifecycle, List<ApplicationStatusHistory> history) {}
}
