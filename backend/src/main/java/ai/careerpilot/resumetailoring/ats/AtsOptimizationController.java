package ai.careerpilot.resumetailoring.ats;

import ai.careerpilot.domain.AtsOptimizationJob;
import ai.careerpilot.domain.ResumeAtsAnalysis;
import ai.careerpilot.resumetailoring.ats.dto.AtsOptimizationDtos.AnalyzeRequest;
import ai.careerpilot.resumetailoring.ats.dto.AtsOptimizationDtos.AtsAnalysisHistoryResponse;
import ai.careerpilot.resumetailoring.ats.dto.AtsOptimizationDtos.AtsAnalysisResponse;
import ai.careerpilot.resumetailoring.ats.dto.AtsOptimizationDtos.AtsOptimizationJobResponse;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2D.2 — ATS Optimization API. New {@code /api/resume/ats/*} namespace, async-first from
 * day one (no synchronous version ever shipped, unlike Resume Tailoring which had to migrate to
 * 202 in 2D.1.1). 404 whenever the engine is disabled or no analysis exists yet — mirrors the
 * {@code ResumeTailoringController} dark-default convention.
 */
@RestController
@RequestMapping("/api/resume/ats")
public class AtsOptimizationController {

    private final AtsOptimizationService optimization;
    private final AtsOptimizationJobService jobs;

    public AtsOptimizationController(AtsOptimizationService optimization, AtsOptimizationJobService jobs) {
        this.optimization = optimization;
        this.jobs = jobs;
    }

    /** POST /api/resume/ats/analyze — enqueue an ATS analysis of the latest tailored resume for a job. */
    @PostMapping("/analyze")
    public ResponseEntity<AtsOptimizationJobResponse> analyze(AuthenticatedUser user, @RequestBody AnalyzeRequest body) {
        UUID jobId = parseJobId(body == null ? null : body.jobId());
        if (!optimization.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ATS optimization is disabled");
        }
        AtsOptimizationJob job = jobs.enqueue(user.userId(), jobId, AtsOptimizationJob.SOURCE_MANUAL);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(AtsOptimizationJobResponse.queued(job));
    }

    /** GET /api/resume/ats/jobs/{jobId} — poll status of a queued/running/completed analysis. */
    @GetMapping("/jobs/{jobId}")
    public AtsOptimizationJobResponse jobStatus(AuthenticatedUser user, @PathVariable UUID jobId) {
        AtsOptimizationJob job = jobs.status(jobId, user.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such ATS optimization job"));
        AtsAnalysisResponse result = jobs.resultOf(job).map(AtsAnalysisResponse::from).orElse(null);
        return AtsOptimizationJobResponse.from(job, result);
    }

    /** GET /api/resume/ats/latest?jobId=... — the latest ATS analysis for a job. */
    @GetMapping("/latest")
    public AtsAnalysisResponse latest(AuthenticatedUser user, @RequestParam String jobId) {
        UUID jid = parseJobId(jobId);
        Optional<ResumeAtsAnalysis> result = optimization.latest(user.userId(), jid);
        return result.map(AtsAnalysisResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no ATS analysis yet"));
    }

    /** GET /api/resume/ats/history?jobId=... — all ATS analyses for a job, newest first. */
    @GetMapping("/history")
    public AtsAnalysisHistoryResponse history(AuthenticatedUser user, @RequestParam String jobId) {
        UUID jid = parseJobId(jobId);
        return AtsAnalysisHistoryResponse.from(optimization.history(user.userId(), jid));
    }

    private static UUID parseJobId(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId is required");
        }
        try {
            return UUID.fromString(jobId.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobId is not a valid UUID");
        }
    }
}
