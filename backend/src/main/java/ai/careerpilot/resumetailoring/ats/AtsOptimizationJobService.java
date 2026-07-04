package ai.careerpilot.resumetailoring.ats;

import ai.careerpilot.domain.AtsOptimizationJob;
import ai.careerpilot.domain.ResumeAtsAnalysis;
import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.repo.AtsOptimizationJobRepository;
import ai.careerpilot.repo.ResumeAtsAnalysisRepository;
import ai.careerpilot.repo.ResumeTailoringRepository;
import ai.careerpilot.resumetailoring.event.AtsOptimizedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2D.2 — the async front door for ATS Optimization, mirroring {@code
 * ResumeTailoringJobService} exactly: persists an {@link AtsOptimizationJob} row up front
 * ({@code QUEUED}), submits the real work to the bounded {@code atsOptimizationExecutor}
 * ({@link AtsOptimizationAsyncConfig}), and updates the job through {@code RUNNING} to a terminal
 * {@code SUCCEEDED}/{@code FAILED} state. {@link AtsOptimizationService#analyze} is unchanged by
 * this queue layer. Built this way from day one — unlike Resume Tailoring, which shipped
 * synchronous first and was hardened in 2D.1.1.
 */
@Service
public class AtsOptimizationJobService {

    private static final Logger log = LoggerFactory.getLogger(AtsOptimizationJobService.class);

    private final AtsOptimizationJobRepository jobs;
    private final ResumeAtsAnalysisRepository analyses;
    private final ResumeTailoringRepository tailorings;
    private final AtsOptimizationService optimization;
    private final ThreadPoolTaskExecutor executor;
    private final ApplicationEventPublisher events;

    public AtsOptimizationJobService(AtsOptimizationJobRepository jobs, ResumeAtsAnalysisRepository analyses,
                                     ResumeTailoringRepository tailorings, AtsOptimizationService optimization,
                                     @Qualifier(AtsOptimizationAsyncConfig.EXECUTOR_BEAN_NAME) ThreadPoolTaskExecutor executor,
                                     ApplicationEventPublisher events) {
        this.jobs = jobs;
        this.analyses = analyses;
        this.tailorings = tailorings;
        this.optimization = optimization;
        this.executor = executor;
        this.events = events;
    }

    /** Enqueue an ATS analysis of the latest tailored resume for (userId, jobId). Always returns a job row, even on immediate rejection. */
    public AtsOptimizationJob enqueue(UUID userId, UUID jobId, String source) {
        UUID resumeTailoringId = tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId)
                .map(ResumeTailoring::getId).orElse(null);

        AtsOptimizationJob job = jobs.save(AtsOptimizationJob.builder()
                .userId(userId).jobId(jobId).resumeTailoringId(resumeTailoringId)
                .source(source)
                .status(AtsOptimizationJob.STATUS_QUEUED)
                .build());

        if (resumeTailoringId == null) {
            job.setStatus(AtsOptimizationJob.STATUS_FAILED);
            job.setErrorReason("no tailored resume exists yet for this job");
            job.setCompletedAt(Instant.now());
            return jobs.save(job);
        }

        try {
            executor.execute(() -> runJob(job.getId(), userId, jobId));
        } catch (TaskRejectedException e) {
            log.warn("ATS_OPTIMIZATION_JOB rejected (queue at capacity) user={} job={}", userId, jobId);
            job.setStatus(AtsOptimizationJob.STATUS_FAILED);
            job.setErrorReason("queue at capacity");
            job.setCompletedAt(Instant.now());
            jobs.save(job);
        }
        return job;
    }

    private void runJob(UUID jobRowId, UUID userId, UUID jobId) {
        AtsOptimizationJob job = jobs.findById(jobRowId).orElse(null);
        if (job == null) return;
        job.setStatus(AtsOptimizationJob.STATUS_RUNNING);
        job.setStartedAt(Instant.now());
        jobs.save(job);
        try {
            Optional<ResumeAtsAnalysis> result = optimization.analyze(userId, jobId);
            if (result.isPresent()) {
                job.setStatus(AtsOptimizationJob.STATUS_SUCCEEDED);
                job.setAtsAnalysisId(result.get().getId());
                events.publishEvent(new AtsOptimizedEvent(userId, jobId,
                        result.get().getResumeTailoringId(), result.get().getId()));
            } else {
                job.setStatus(AtsOptimizationJob.STATUS_FAILED);
                job.setErrorReason("analysis could not be generated (missing tailoring/job, or LLM failure)");
            }
        } catch (Exception e) {
            job.setStatus(AtsOptimizationJob.STATUS_FAILED);
            job.setErrorReason(e.toString());
            log.warn("ATS_OPTIMIZATION_JOB error user={} job={}: {}", userId, jobId, e.toString());
        } finally {
            job.setCompletedAt(Instant.now());
            jobs.save(job);
        }
    }

    public Optional<AtsOptimizationJob> status(UUID jobRowId, UUID userId) {
        return jobs.findByIdAndUserId(jobRowId, userId);
    }

    public Optional<ResumeAtsAnalysis> resultOf(AtsOptimizationJob job) {
        return job.getAtsAnalysisId() == null ? Optional.empty() : analyses.findById(job.getAtsAnalysisId());
    }
}
