package ai.careerpilot.workflow.api;

import ai.careerpilot.repo.WorkflowCorrelationRepository;
import ai.careerpilot.repo.WorkflowDeadLetterRepository;
import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.workflow.analytics.ApplicationAnalyticsMetrics;
import ai.careerpilot.workflow.career.CareerIntelligenceMetrics;
import ai.careerpilot.workflow.config.WorkflowExecutorsConfig;
import ai.careerpilot.workflow.correlation.WorkflowMetrics;
import ai.careerpilot.workflow.email.EmailIntelligenceMetrics;
import ai.careerpilot.workflow.interview.InterviewMetrics;
import ai.careerpilot.workflow.timeline.TimelineMetrics;
import ai.careerpilot.workflow.tracking.WorkflowTrackingMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 3A — eight no-auth, counts-only diagnostics endpoints (no application content, no PII), one per
 * workflow stage plus the two Phase 3A.0 infra surfaces (correlation, dead-letter). Same convention and
 * exact {@code stage(...)} health verdict (NOT_CONFIGURED | UP | DEGRADED | DOWN) as
 * {@code ExecutionDiagnosticsController} (2E). With stock (dark) defaults every engine stage reads
 * {@code NOT_CONFIGURED}, proving the whole workflow registers without activating.
 */
@RestController
@RequestMapping("/api/diagnostics")
public class WorkflowDiagnosticsController {

    private final WorkflowTrackingMetrics trackingMetrics;
    private final TimelineMetrics timelineMetrics;
    private final EmailIntelligenceMetrics emailMetrics;
    private final InterviewMetrics interviewMetrics;
    private final ApplicationAnalyticsMetrics analyticsMetrics;
    private final CareerIntelligenceMetrics careerMetrics;
    private final WorkflowMetrics workflowMetrics;
    private final WorkflowCorrelationRepository correlationRepo;
    private final WorkflowDeadLetterRepository deadLetterRepo;

    private final ThreadPoolTaskExecutor trackingExecutor;
    private final ThreadPoolTaskExecutor timelineExecutor;
    private final ThreadPoolTaskExecutor emailExecutor;
    private final ThreadPoolTaskExecutor interviewExecutor;
    private final ThreadPoolTaskExecutor analyticsExecutor;
    private final ThreadPoolTaskExecutor careerExecutor;

    @Value("${workflow.tracking.enabled:false}") private boolean trackingEnabled;
    @Value("${workflow.tracking.trigger.enabled:false}") private boolean trackingTriggerEnabled;
    @Value("${workflow.timeline.enabled:false}") private boolean timelineEnabled;
    @Value("${workflow.timeline.trigger.enabled:false}") private boolean timelineTriggerEnabled;
    @Value("${email.intelligence.enabled:false}") private boolean emailEnabled;
    @Value("${email.intelligence.trigger.enabled:false}") private boolean emailTriggerEnabled;
    @Value("${interview.tracking.enabled:false}") private boolean interviewEnabled;
    @Value("${interview.tracking.trigger.enabled:false}") private boolean interviewTriggerEnabled;
    @Value("${workflow.analytics.enabled:false}") private boolean analyticsEnabled;
    @Value("${workflow.analytics.trigger.enabled:false}") private boolean analyticsTriggerEnabled;
    @Value("${career.intelligence.enabled:false}") private boolean careerEnabled;
    @Value("${career.intelligence.trigger.enabled:false}") private boolean careerTriggerEnabled;

    public WorkflowDiagnosticsController(
            WorkflowTrackingMetrics trackingMetrics, TimelineMetrics timelineMetrics,
            EmailIntelligenceMetrics emailMetrics, InterviewMetrics interviewMetrics,
            ApplicationAnalyticsMetrics analyticsMetrics, CareerIntelligenceMetrics careerMetrics,
            WorkflowMetrics workflowMetrics, WorkflowCorrelationRepository correlationRepo,
            WorkflowDeadLetterRepository deadLetterRepo,
            @Qualifier(WorkflowExecutorsConfig.APPLICATION_TRACKING_EXECUTOR) ThreadPoolTaskExecutor trackingExecutor,
            @Qualifier(WorkflowExecutorsConfig.TIMELINE_EXECUTOR) ThreadPoolTaskExecutor timelineExecutor,
            @Qualifier(WorkflowExecutorsConfig.EMAIL_INTELLIGENCE_EXECUTOR) ThreadPoolTaskExecutor emailExecutor,
            @Qualifier(WorkflowExecutorsConfig.INTERVIEW_TRACKING_EXECUTOR) ThreadPoolTaskExecutor interviewExecutor,
            @Qualifier(WorkflowExecutorsConfig.CAREER_ANALYTICS_EXECUTOR) ThreadPoolTaskExecutor analyticsExecutor,
            @Qualifier(WorkflowExecutorsConfig.CAREER_INTELLIGENCE_EXECUTOR) ThreadPoolTaskExecutor careerExecutor) {
        this.trackingMetrics = trackingMetrics;
        this.timelineMetrics = timelineMetrics;
        this.emailMetrics = emailMetrics;
        this.interviewMetrics = interviewMetrics;
        this.analyticsMetrics = analyticsMetrics;
        this.careerMetrics = careerMetrics;
        this.workflowMetrics = workflowMetrics;
        this.correlationRepo = correlationRepo;
        this.deadLetterRepo = deadLetterRepo;
        this.trackingExecutor = trackingExecutor;
        this.timelineExecutor = timelineExecutor;
        this.emailExecutor = emailExecutor;
        this.interviewExecutor = interviewExecutor;
        this.analyticsExecutor = analyticsExecutor;
        this.careerExecutor = careerExecutor;
    }

    @GetMapping("/application-tracking")
    public Map<String, Object> applicationTracking() {
        Map<String, Object> m = trackingMetrics.snapshot();
        return stage(trackingEnabled, trackingTriggerEnabled, m, trackingExecutor,
                (Long) m.get("trackingTotal"), (Long) m.get("trackingFailures"));
    }

    @GetMapping("/application-timeline")
    public Map<String, Object> applicationTimeline() {
        Map<String, Object> m = timelineMetrics.snapshot();
        return stage(timelineEnabled, timelineTriggerEnabled, m, timelineExecutor,
                (Long) m.get("timelineTotal"), (Long) m.get("timelineFailures"));
    }

    @GetMapping("/email-intelligence")
    public Map<String, Object> emailIntelligence() {
        Map<String, Object> m = emailMetrics.snapshot();
        return stage(emailEnabled, emailTriggerEnabled, m, emailExecutor,
                (Long) m.get("emailTotal"), (Long) m.get("emailFailures"));
    }

    @GetMapping("/interview-tracking")
    public Map<String, Object> interviewTracking() {
        Map<String, Object> m = interviewMetrics.snapshot();
        return stage(interviewEnabled, interviewTriggerEnabled, m, interviewExecutor,
                (Long) m.get("interviewTotal"), (Long) m.get("interviewFailures"));
    }

    @GetMapping("/application-analytics")
    public Map<String, Object> applicationAnalytics() {
        Map<String, Object> m = analyticsMetrics.snapshot();
        return stage(analyticsEnabled, analyticsTriggerEnabled, m, analyticsExecutor,
                (Long) m.get("analyticsTotal"), (Long) m.get("analyticsFailures"));
    }

    @GetMapping("/career-intelligence")
    public Map<String, Object> careerIntelligence() {
        Map<String, Object> m = careerMetrics.snapshot();
        return stage(careerEnabled, careerTriggerEnabled, m, careerExecutor,
                (Long) m.get("careerTotal"), (Long) m.get("careerFailures"));
    }

    /**
     * Phase 3A.0 infra — the correlation ledger. Always registered (no feature flag): the framework is
     * inert only because no worker calls it with stock flags. Health is UP unless correlation writes are
     * failing.
     */
    @GetMapping("/workflow-correlation")
    public Map<String, Object> workflowCorrelation() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(workflowMetrics.correlationSnapshot());
        out.put("correlationsStartedTotal", correlationRepo.countByStatus(WorkflowCorrelation.STATUS_STARTED));
        out.put("correlationsInProgressTotal", correlationRepo.countByStatus(WorkflowCorrelation.STATUS_IN_PROGRESS));
        out.put("correlationsCompletedTotal", correlationRepo.countByStatus(WorkflowCorrelation.STATUS_COMPLETED));
        out.put("correlationsFailedTotal", correlationRepo.countByStatus(WorkflowCorrelation.STATUS_FAILED));
        Long failures = (Long) out.get("correlationFailures");
        out.put("health", failures != null && failures > 0 ? "DEGRADED" : "UP");
        return out;
    }

    /**
     * Phase 3A.0 infra — the dead-letter ledger. A non-zero count is not itself unhealthy (it is the
     * system working as designed: a failed stage is captured, not lost); only a failure to <em>record</em>
     * a dead-letter is DEGRADED.
     */
    @GetMapping("/workflow-dead-letter")
    public Map<String, Object> workflowDeadLetter() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(workflowMetrics.deadLetterSnapshot());
        out.put("deadLetterRowsTotal", deadLetterRepo.count());
        Long recordFailures = (Long) out.get("deadLetterFailures");
        out.put("health", recordFailures != null && recordFailures > 0 ? "DEGRADED" : "UP");
        return out;
    }

    private static Map<String, Object> stage(boolean enabled, boolean triggerEnabled,
                                             Map<String, Object> metrics, ThreadPoolTaskExecutor executor,
                                             Long total, Long failures) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("triggerEnabled", triggerEnabled);
        out.putAll(metrics);
        int queueSize = executor.getThreadPoolExecutor().getQueue().size();
        out.put("executorActiveCount", executor.getActiveCount());
        out.put("executorPoolSize", executor.getPoolSize());
        out.put("executorQueueSize", queueSize);
        out.put("executorQueueCapacity", executor.getQueueCapacity());

        String health;
        if (!enabled) {
            health = "NOT_CONFIGURED";
        } else if (queueSize >= executor.getQueueCapacity()) {
            health = "DOWN";
        } else if (total != null && total > 0 && failures != null && failures * 100 > total * 30) {
            health = "DEGRADED";
        } else {
            health = "UP";
        }
        out.put("health", health);
        return out;
    }
}
