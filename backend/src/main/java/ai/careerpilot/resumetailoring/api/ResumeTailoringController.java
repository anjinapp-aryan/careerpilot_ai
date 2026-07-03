package ai.careerpilot.resumetailoring.api;

import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.domain.ResumeTailoringJob;
import ai.careerpilot.resumetailoring.dto.ResumeTailoringDtos.TailorRequest;
import ai.careerpilot.resumetailoring.dto.ResumeTailoringDtos.TailoredResumeResponse;
import ai.careerpilot.resumetailoring.dto.ResumeTailoringDtos.TailoringHistoryResponse;
import ai.careerpilot.resumetailoring.dto.ResumeTailoringJobDtos.TailoringJobResponse;
import ai.careerpilot.resumetailoring.service.ResumeTailoringJobService;
import ai.careerpilot.resumetailoring.service.ResumeTailoringService;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2D.1 (Step 11) / Phase 2D.1.1 (async hardening) — Resume Tailoring API. New
 * {@code /api/resume/*} namespace; the existing resume upload/optimize contracts are untouched
 * (ZERO API contract changes there). 404 whenever the engine is disabled or no tailoring exists
 * yet — mirrors the {@code RecommendationController} dark-default convention from Phase 2C.
 *
 * <p>Generation endpoints ({@code /tailor}, {@code /tailored/rebuild}) return <b>202 Accepted</b>
 * with a job resource rather than blocking for the LLM call (live-measured P95 was 75-117s under
 * provider degradation — see the Phase 2D.1 sign-off). Poll {@code GET /tailor/jobs/{jobId}}
 * until {@code status=SUCCEEDED}/{@code FAILED}. The frontend does not call this namespace yet, so
 * this is not a breaking change for any existing client.
 */
@RestController
@RequestMapping("/api/resume")
public class ResumeTailoringController {

    private final ResumeTailoringService tailoring;
    private final ResumeTailoringJobService jobs;

    public ResumeTailoringController(ResumeTailoringService tailoring, ResumeTailoringJobService jobs) {
        this.tailoring = tailoring;
        this.jobs = jobs;
    }

    /** POST /api/resume/tailor — enqueue generation (or cache lookup) of a tailored resume for a job. */
    @PostMapping("/tailor")
    public ResponseEntity<TailoringJobResponse> tailor(AuthenticatedUser user, @RequestBody TailorRequest body) {
        UUID jobId = parseJobId(body == null ? null : body.jobId());
        if (!tailoring.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "resume tailoring is disabled");
        }
        ResumeTailoringJob job = jobs.enqueue(user.userId(), jobId, null, ResumeTailoringJob.SOURCE_MANUAL);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(TailoringJobResponse.queued(job));
    }

    /** GET /api/resume/tailor/jobs/{jobId} — poll status of a queued/running/completed generation. */
    @GetMapping("/tailor/jobs/{jobId}")
    public TailoringJobResponse jobStatus(AuthenticatedUser user, @PathVariable UUID jobId) {
        ResumeTailoringJob job = jobs.status(jobId, user.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such tailoring job"));
        TailoredResumeResponse result = jobs.resultOf(job).map(TailoredResumeResponse::from).orElse(null);
        return TailoringJobResponse.from(job, result);
    }

    /** GET /api/resume/tailored?jobId=... — the latest tailored version for a job. */
    @GetMapping("/tailored")
    public TailoredResumeResponse latest(AuthenticatedUser user, @RequestParam String jobId) {
        UUID jid = parseJobId(jobId);
        Optional<ResumeTailoring> result = tailoring.latest(user.userId(), jid);
        return result.map(TailoredResumeResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no tailored resume yet"));
    }

    /** GET /api/resume/tailored/history — this user's tailoring history across all jobs. */
    @GetMapping("/tailored/history")
    public TailoringHistoryResponse history(AuthenticatedUser user) {
        return TailoringHistoryResponse.from(tailoring.history(user.userId()));
    }

    /** POST /api/resume/tailored/rebuild — enqueue a forced fresh generation, bypassing the cache. */
    @PostMapping("/tailored/rebuild")
    public ResponseEntity<TailoringJobResponse> rebuild(AuthenticatedUser user, @RequestBody TailorRequest body) {
        UUID jobId = parseJobId(body == null ? null : body.jobId());
        if (!tailoring.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "resume tailoring is disabled");
        }
        ResumeTailoringJob job = jobs.enqueueRebuild(user.userId(), jobId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(TailoringJobResponse.queued(job));
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
