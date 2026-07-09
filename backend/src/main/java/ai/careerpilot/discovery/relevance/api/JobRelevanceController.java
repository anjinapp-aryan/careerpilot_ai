package ai.careerpilot.discovery.relevance.api;

import ai.careerpilot.discovery.relevance.CareerRelevanceEvaluator;
import ai.careerpilot.discovery.relevance.CareerRelevanceExplanation;
import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Phase 3B.1 — Step 10 explainability surface. A new, additive controller (mirrors the
 * {@code WorkflowController}/{@code WorkflowTraceController} precedent of multiple
 * {@code @RestController}s sharing one base path) rather than touching the existing
 * {@code JobController}. Gated by {@code career.explainability.enabled}: 404 when disabled.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobRelevanceController {

    private final JobRepository jobs;
    private final CareerRelevanceEvaluator evaluator;

    public JobRelevanceController(JobRepository jobs, CareerRelevanceEvaluator evaluator) {
        this.jobs = jobs;
        this.evaluator = evaluator;
    }

    /**
     * GET /api/jobs/{id}/relevance — career-relevance explanation for the current user against
     * one job. Same multi-tenant guard as {@code JobMatchExplanationService.explain}: global
     * discovered jobs (org_id NULL) are visible to all; org jobs only to their own org.
     */
    @GetMapping("/{id}/relevance")
    public ResponseEntity<CareerRelevanceExplanation> relevance(AuthenticatedUser user, @PathVariable UUID id) {
        Job job = jobs.findById(id).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        if (job.getOrgId() != null && !job.getOrgId().equals(user.orgId())) {
            return ResponseEntity.notFound().build();
        }
        return evaluator.explain(user.userId(), job)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
