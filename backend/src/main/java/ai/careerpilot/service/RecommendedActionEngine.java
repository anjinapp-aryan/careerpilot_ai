package ai.careerpilot.service;

import ai.careerpilot.mission.MissionOrchestratorService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Phase 11B — a deterministic (no LLM, no repository access of its own) priority engine over an
 * already-assembled {@link CareerContextService.CareerContext}. Every rule below reads a field
 * that {@link CareerContextService} already fetched for the same turn — this class performs zero
 * additional queries, so wiring it into {@link CareerContextService#getCareerContext} never adds
 * an N+1 or a duplicate lookup.
 *
 * <p>Same discipline as every other rule-based engine in this codebase ({@code
 * MissionOrchestratorService.decide}, {@code CountryMatchingCapability}, {@code
 * StrategyEvaluationService}): a fixed priority ladder over real, already-verified signals, never
 * a guessed or LLM-invented ranking. A signal this engine has no real data for (e.g. resume
 * staleness — not part of {@link CareerContextService.CareerContext}) is simply not a rule here
 * rather than being approximated.
 */
@Service
public class RecommendedActionEngine {

    public record RecommendedAction(int priority, String category, String title, String reason) {}

    /** Lower number = higher priority, matching the phase spec's own worked ranking. */
    private static final int PRIORITY_MANUAL_SUBMISSION = 1;
    private static final int PRIORITY_UPCOMING_INTERVIEW = 2;
    private static final int PRIORITY_WORKFLOW_AWAITING_APPROVAL = 3;
    private static final int PRIORITY_MISSION_GUIDANCE = 4;
    private static final int PRIORITY_WORKFLOW_FAILED = 5;
    private static final int PRIORITY_REJECTED_APPLICATIONS = 6;

    public List<RecommendedAction> derive(CareerContextService.CareerContext ctx) {
        List<RecommendedAction> actions = new ArrayList<>();
        if (ctx == null) return actions;

        var applications = ctx.applications();
        if (applications != null && applications.waitingManualSubmission() > 0) {
            actions.add(new RecommendedAction(PRIORITY_MANUAL_SUBMISSION, "Applications",
                    "Complete manual submission for " + applications.waitingManualSubmission()
                            + " application(s)",
                    "These applications are packaged and approved but require you to submit them "
                            + "manually — they will not progress on their own."));
        }

        var interview = ctx.interviews();
        if (interview != null && interview.latestScheduledAt() != null
                && interview.latestScheduledAt().isAfter(Instant.now())) {
            actions.add(new RecommendedAction(PRIORITY_UPCOMING_INTERVIEW, "Interview",
                    "Prepare for your upcoming " + nullSafe(interview.latestType()) + " interview",
                    "Scheduled for " + interview.latestScheduledAt() + "."));
        }

        var workflow = ctx.workflow();
        if (workflow != null && workflow.interruptedCount() > 0) {
            actions.add(new RecommendedAction(PRIORITY_WORKFLOW_AWAITING_APPROVAL, "Workflow",
                    workflow.interruptedCount() + " workflow run(s) awaiting your approval",
                    "These runs are paused until you review and approve or reject them."));
        }

        var mission = ctx.mission();
        if (mission != null && mission.recommendedNext() != null) {
            for (MissionOrchestratorService.Decision decision : mission.recommendedNext()) {
                actions.add(new RecommendedAction(PRIORITY_MISSION_GUIDANCE, "Mission",
                        "Run " + decision.workflowId() + " toward your mission",
                        decision.reason()));
            }
        }

        if (workflow != null && workflow.failedCount() > 0) {
            actions.add(new RecommendedAction(PRIORITY_WORKFLOW_FAILED, "Workflow",
                    workflow.failedCount() + " workflow run(s) failed",
                    "Review the failed run(s) to see what went wrong before retrying."));
        }

        if (applications != null) {
            Long rejected = applications.countByStatus().get("REJECTED");
            if (rejected != null && rejected > 0) {
                actions.add(new RecommendedAction(PRIORITY_REJECTED_APPLICATIONS, "Applications",
                        "Review " + rejected + " rejected application(s) for patterns",
                        "Looking for a recurring gap (skill, seniority, location) across rejections "
                                + "can sharpen future applications."));
            }
        }

        return actions.stream()
                .sorted(Comparator.comparingInt(RecommendedAction::priority))
                .toList();
    }

    private static String nullSafe(String s) {
        return s == null || s.isBlank() ? "your" : s;
    }
}
