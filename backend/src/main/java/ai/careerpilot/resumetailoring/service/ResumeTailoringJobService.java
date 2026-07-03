package ai.careerpilot.resumetailoring.service;

import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.domain.ResumeTailoringJob;
import ai.careerpilot.repo.ResumeTailoringJobRepository;
import ai.careerpilot.repo.ResumeTailoringRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2D.1.1 — the async front door for the tailoring engine. Persists a {@link
 * ResumeTailoringJob} row up front ({@code QUEUED}), submits the real work to the bounded {@code
 * resumeTailoringExecutor} ({@link ai.careerpilot.resumetailoring.config.ResumeTailoringAsyncConfig}),
 * and updates the job row through {@code RUNNING} to a terminal {@code SUCCEEDED}/{@code FAILED}
 * state. The underlying {@link ResumeTailoringService#tailor}/{@link ResumeTailoringService#rebuild}
 * methods are unchanged — this class only adds a queue + status layer in front of them so both the
 * manual API path and {@link ResumeTailoringWorker}'s approve-triggered path share one bounded pool
 * and one observable job table (see queue/health diagnostics on {@code DiagnosticsController}).
 *
 * <p>A full queue throws {@link TaskRejectedException} from {@link
 * org.springframework.core.task.TaskExecutor#execute}; that is caught here and turned into an
 * immediate terminal {@code FAILED} row rather than letting callers see a raw 5xx or piling up
 * unbounded work.
 */
@Service
public class ResumeTailoringJobService {

    private static final Logger log = LoggerFactory.getLogger(ResumeTailoringJobService.class);

    private final ResumeTailoringJobRepository jobs;
    private final ResumeTailoringRepository tailorings;
    private final ResumeTailoringService tailoring;
    private final ThreadPoolTaskExecutor executor;

    public ResumeTailoringJobService(ResumeTailoringJobRepository jobs, ResumeTailoringRepository tailorings,
                                     ResumeTailoringService tailoring, ThreadPoolTaskExecutor executor) {
        this.jobs = jobs;
        this.tailorings = tailorings;
        this.tailoring = tailoring;
        this.executor = executor;
    }

    /** Enqueue a manual (or approve-triggered) tailoring generation. Always returns a job row, even on immediate rejection. */
    public ResumeTailoringJob enqueue(UUID userId, UUID jobId, UUID recommendationAuditIdHint, String source) {
        return submit(userId, jobId, recommendationAuditIdHint, source, false);
    }

    /** Enqueue a forced regeneration (bypasses the tailoring cache), mirroring {@code ResumeTailoringService.rebuild}. */
    public ResumeTailoringJob enqueueRebuild(UUID userId, UUID jobId) {
        return submit(userId, jobId, null, ResumeTailoringJob.SOURCE_MANUAL, true);
    }

    private ResumeTailoringJob submit(UUID userId, UUID jobId, UUID recommendationAuditIdHint, String source, boolean rebuild) {
        ResumeTailoringJob job = jobs.save(ResumeTailoringJob.builder()
                .userId(userId).jobId(jobId)
                .recommendationAuditId(recommendationAuditIdHint)
                .source(source)
                .status(ResumeTailoringJob.STATUS_QUEUED)
                .build());
        try {
            executor.execute(() -> runJob(job.getId(), userId, jobId, recommendationAuditIdHint, rebuild));
        } catch (TaskRejectedException e) {
            log.warn("RESUME_TAILORING_JOB rejected (queue at capacity) user={} job={}", userId, jobId);
            job.setStatus(ResumeTailoringJob.STATUS_FAILED);
            job.setErrorReason("queue at capacity");
            job.setCompletedAt(Instant.now());
            jobs.save(job);
        }
        return job;
    }

    private void runJob(UUID jobRowId, UUID userId, UUID jobId, UUID recommendationAuditIdHint, boolean rebuild) {
        ResumeTailoringJob job = jobs.findById(jobRowId).orElse(null);
        if (job == null) return;
        job.setStatus(ResumeTailoringJob.STATUS_RUNNING);
        job.setStartedAt(Instant.now());
        jobs.save(job);
        try {
            Optional<ResumeTailoring> result = rebuild
                    ? tailoring.rebuild(userId, jobId)
                    : tailoring.tailor(userId, jobId, recommendationAuditIdHint);
            if (result.isPresent()) {
                job.setStatus(ResumeTailoringJob.STATUS_SUCCEEDED);
                job.setResumeTailoringId(result.get().getId());
            } else {
                job.setStatus(ResumeTailoringJob.STATUS_FAILED);
                job.setErrorReason("tailoring could not be generated (missing resume/job, or failed validation) — see resume_tailoring_audit");
            }
        } catch (Exception e) {
            job.setStatus(ResumeTailoringJob.STATUS_FAILED);
            job.setErrorReason(e.toString());
            log.warn("RESUME_TAILORING_JOB error user={} job={}: {}", userId, jobId, e.toString());
        } finally {
            job.setCompletedAt(Instant.now());
            jobs.save(job);
        }
    }

    public Optional<ResumeTailoringJob> status(UUID jobRowId, UUID userId) {
        return jobs.findByIdAndUserId(jobRowId, userId);
    }

    public Optional<ResumeTailoring> resultOf(ResumeTailoringJob job) {
        return job.getResumeTailoringId() == null ? Optional.empty() : tailorings.findById(job.getResumeTailoringId());
    }
}
