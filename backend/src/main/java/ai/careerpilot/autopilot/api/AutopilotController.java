package ai.careerpilot.autopilot.api;

import ai.careerpilot.autopilot.apply.AutoApplyEngine;
import ai.careerpilot.autopilot.decision.ApplicationDecisionEngine;
import ai.careerpilot.autopilot.orchestrator.CareerOrchestrator;
import ai.careerpilot.autopilot.orchestrator.CareerOrchestrator.AutopilotRunSummary;
import ai.careerpilot.autopilot.prep.InterviewPreparationService;
import ai.careerpilot.autopilot.prep.InterviewPreparationService.InterviewPlan;
import ai.careerpilot.autopilot.research.CompanyResearchEngine;
import ai.careerpilot.autopilot.research.CompanyResearchEngine.CompanyResearch;
import ai.careerpilot.domain.ApplicationDecision;
import ai.careerpilot.domain.ApplicationSubmission;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Phase 7 — the authenticated human surface over the autonomous agent, distinct from the no-auth
 * diagnostics. Every read is scoped to the caller ({@code user.userId()}) exactly like the rest of
 * the app. All engines ship dark, so with stock flags the reads return 404/absent and the run
 * trigger is a no-op summary.
 */
@RestController
@RequestMapping("/api/autopilot")
public class AutopilotController {

    private final ApplicationDecisionEngine decisionEngine;
    private final AutoApplyEngine autoApplyEngine;
    private final InterviewPreparationService interviewPrep;
    private final CompanyResearchEngine companyResearch;
    private final CareerOrchestrator orchestrator;

    public AutopilotController(ApplicationDecisionEngine decisionEngine, AutoApplyEngine autoApplyEngine,
                              InterviewPreparationService interviewPrep, CompanyResearchEngine companyResearch,
                              CareerOrchestrator orchestrator) {
        this.decisionEngine = decisionEngine;
        this.autoApplyEngine = autoApplyEngine;
        this.interviewPrep = interviewPrep;
        this.companyResearch = companyResearch;
        this.orchestrator = orchestrator;
    }

    /** Latest autonomous decision for a job. 404 when none / engine dark. */
    @GetMapping("/decisions/{jobId}")
    public ResponseEntity<ApplicationDecision> decision(AuthenticatedUser user, @PathVariable UUID jobId) {
        return decisionEngine.latest(user.userId(), jobId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Latest submission attempt for a job. 404 when none / engine dark. */
    @GetMapping("/submissions/{jobId}")
    public ResponseEntity<ApplicationSubmission> submission(AuthenticatedUser user, @PathVariable UUID jobId) {
        return autoApplyEngine.latest(user.userId(), jobId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Generate (or fail-safe empty) an interview-prep plan. 404 when disabled / no data. */
    @GetMapping("/interview-prep/{jobId}")
    public ResponseEntity<InterviewPlan> interviewPrep(AuthenticatedUser user, @PathVariable UUID jobId) {
        return interviewPrep.prepare(user.userId(), jobId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Company research notes for a job. 404 when disabled / no data. */
    @GetMapping("/company-research/{jobId}")
    public ResponseEntity<CompanyResearch> companyResearch(AuthenticatedUser user, @PathVariable UUID jobId) {
        return companyResearch.research(jobId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Manually run the orchestrator for the current user. Returns a no-op summary when disabled. */
    @PostMapping("/run")
    public AutopilotRunSummary run(AuthenticatedUser user) {
        return orchestrator.runForUser(user.userId(), user.orgId());
    }
}
