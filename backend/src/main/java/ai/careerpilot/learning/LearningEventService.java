package ai.careerpilot.learning;

import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.LearningMetricsLog;
import ai.careerpilot.learning.event.LearningEventRecordedEvent;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.LearningMetricsLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Phase 6.1 — persists one {@link LearningEvent} row and publishes {@link LearningEventRecordedEvent}
 * to drive the downstream pattern-learning stages. Never throws: a persistence failure is logged
 * and swallowed by the caller ({@code LearningEventBridge}), which routes it to the shared
 * {@code WorkflowDeadLetterService} sink instead.
 */
@Service
public class LearningEventService {

    private static final Logger log = LoggerFactory.getLogger(LearningEventService.class);

    private final LearningEventRepository events;
    private final LearningMetricsLogRepository metricsLog;
    private final JobRepository jobs;
    private final LearningMetrics metrics;
    private final ApplicationEventPublisher publisher;

    public LearningEventService(LearningEventRepository events, LearningMetricsLogRepository metricsLog,
                                JobRepository jobs, LearningMetrics metrics, ApplicationEventPublisher publisher) {
        this.events = events;
        this.metricsLog = metricsLog;
        this.jobs = jobs;
        this.metrics = metrics;
        this.publisher = publisher;
    }

    @Transactional
    public void record(LearningEventType type, UUID correlationId, UUID userId, UUID jobId,
                       String resumeVersion, String workflowId) {
        long start = System.currentTimeMillis();
        LearningEvent.LearningEventBuilder builder = LearningEvent.builder()
                .correlationId(correlationId).userId(userId).jobId(jobId)
                .eventType(type.name()).resumeVersion(resumeVersion).workflowId(workflowId);

        if (jobId != null) {
            jobs.findById(jobId).ifPresent(job -> enrich(builder, job));
        }

        LearningEvent saved = events.save(builder.build());
        metrics.recordSuccess(LearningMetricsLog.STAGE_EVENT_CAPTURE);
        metricsLog.save(LearningMetricsLog.builder()
                .learningEventId(saved.getId())
                .stage(LearningMetricsLog.STAGE_EVENT_CAPTURE)
                .status(LearningMetricsLog.STATUS_SUCCESS)
                .latencyMs(System.currentTimeMillis() - start)
                .build());

        publisher.publishEvent(new LearningEventRecordedEvent(saved.getId(), correlationId, userId, jobId, type));
        log.debug("LEARNING_EVENT type={} user={} job={}", type, userId, jobId);
    }

    private static void enrich(LearningEvent.LearningEventBuilder builder, Job job) {
        builder.company(job.getCompany()).country(job.getCountry())
                .roleFamily(job.getJobFamily()).skills(job.getSkills())
                .industry(job.getJobFamily())
                .salaryBand(salaryBand(job));
    }

    private static String salaryBand(Job job) {
        if (job.getSalaryMin() == null && job.getSalaryMax() == null) return null;
        double mid = (nz(job.getSalaryMin()) + nz(job.getSalaryMax())) / 2.0;
        if (mid <= 0) return null;
        if (mid < 60_000) return "UNDER_60K";
        if (mid < 100_000) return "60K_100K";
        if (mid < 150_000) return "100K_150K";
        if (mid < 200_000) return "150K_200K";
        return "OVER_200K";
    }

    private static double nz(java.math.BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }
}
