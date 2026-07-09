package ai.careerpilot.workflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Phase 3A — seven dedicated, bounded executors, one per workflow stage. NO shared pools: each
 * stage's queue/health signals stay independent, exactly like
 * {@link ai.careerpilot.execution.config.ExecutionExecutorsConfig} (2E) and
 * {@code PipelineExecutorsConfig} (2D).
 *
 * <p>{@code careerAnalyticsExecutor} is deliberately named to avoid colliding with Phase 2E's
 * {@code analyticsExecutor} bean — the two analytics engines are separate bounded contexts.
 *
 * <p>All use the default {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy}: a full queue
 * rejects immediately and the worker turns it into a dead-letter row, never a silent unbounded
 * backlog.
 */
@Configuration
public class WorkflowExecutorsConfig {

    public static final String APPLICATION_TRACKING_EXECUTOR = "applicationTrackingExecutor";
    public static final String STATUS_DETECTION_EXECUTOR = "statusDetectionExecutor";
    public static final String TIMELINE_EXECUTOR = "timelineExecutor";
    public static final String EMAIL_INTELLIGENCE_EXECUTOR = "emailIntelligenceExecutor";
    public static final String INTERVIEW_TRACKING_EXECUTOR = "interviewTrackingExecutor";
    public static final String CAREER_ANALYTICS_EXECUTOR = "careerAnalyticsExecutor";
    public static final String CAREER_INTELLIGENCE_EXECUTOR = "careerIntelligenceExecutor";

    @Bean(name = APPLICATION_TRACKING_EXECUTOR)
    public ThreadPoolTaskExecutor applicationTrackingExecutor(
            @Value("${workflow.tracking.executor.core-pool-size:2}") int core,
            @Value("${workflow.tracking.executor.max-pool-size:4}") int max,
            @Value("${workflow.tracking.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "wf-tracking-");
    }

    @Bean(name = STATUS_DETECTION_EXECUTOR)
    public ThreadPoolTaskExecutor statusDetectionExecutor(
            @Value("${workflow.status-detection.executor.core-pool-size:2}") int core,
            @Value("${workflow.status-detection.executor.max-pool-size:4}") int max,
            @Value("${workflow.status-detection.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "wf-status-");
    }

    @Bean(name = TIMELINE_EXECUTOR)
    public ThreadPoolTaskExecutor timelineExecutor(
            @Value("${workflow.timeline.executor.core-pool-size:2}") int core,
            @Value("${workflow.timeline.executor.max-pool-size:4}") int max,
            @Value("${workflow.timeline.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "wf-timeline-");
    }

    @Bean(name = EMAIL_INTELLIGENCE_EXECUTOR)
    public ThreadPoolTaskExecutor emailIntelligenceExecutor(
            @Value("${email.intelligence.executor.core-pool-size:1}") int core,
            @Value("${email.intelligence.executor.max-pool-size:2}") int max,
            @Value("${email.intelligence.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "wf-email-");
    }

    @Bean(name = INTERVIEW_TRACKING_EXECUTOR)
    public ThreadPoolTaskExecutor interviewTrackingExecutor(
            @Value("${interview.tracking.executor.core-pool-size:2}") int core,
            @Value("${interview.tracking.executor.max-pool-size:4}") int max,
            @Value("${interview.tracking.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "wf-interview-");
    }

    @Bean(name = CAREER_ANALYTICS_EXECUTOR)
    public ThreadPoolTaskExecutor careerAnalyticsExecutor(
            @Value("${workflow.analytics.executor.core-pool-size:1}") int core,
            @Value("${workflow.analytics.executor.max-pool-size:2}") int max,
            @Value("${workflow.analytics.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "wf-analytics-");
    }

    @Bean(name = CAREER_INTELLIGENCE_EXECUTOR)
    public ThreadPoolTaskExecutor careerIntelligenceExecutor(
            @Value("${career.intelligence.executor.core-pool-size:1}") int core,
            @Value("${career.intelligence.executor.max-pool-size:2}") int max,
            @Value("${career.intelligence.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "wf-career-");
    }

    private static ThreadPoolTaskExecutor build(int core, int max, int queue, String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
