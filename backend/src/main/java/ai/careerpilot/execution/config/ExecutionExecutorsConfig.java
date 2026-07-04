package ai.careerpilot.execution.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Phase 2E — five dedicated, bounded executors, one per execution-pipeline stage (Application
 * Execution, Browser Automation, ATS Connector, Tracking, Analytics). NO shared pools: each
 * stage's queue/health signals stay independent, exactly like
 * {@link ai.careerpilot.resumetailoring.config.PipelineExecutorsConfig} for Phase 2D.
 *
 * <p>All use the default {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy} — a full
 * queue rejects immediately; the worker/job-service catches and turns it into a terminal FAILED
 * state, never a silent unbounded backlog. This is the highest-risk phase in the product, so the
 * executors are small on purpose and every stage is independently observable and independently
 * gated.
 */
@Configuration
public class ExecutionExecutorsConfig {

    public static final String APPLICATION_EXECUTION_EXECUTOR = "applicationExecutionExecutor";
    public static final String BROWSER_AUTOMATION_EXECUTOR = "browserAutomationExecutor";
    public static final String ATS_CONNECTOR_EXECUTOR = "atsConnectorExecutor";
    public static final String TRACKING_EXECUTOR = "trackingExecutor";
    public static final String ANALYTICS_EXECUTOR = "analyticsExecutor";

    @Bean(name = APPLICATION_EXECUTION_EXECUTOR)
    public ThreadPoolTaskExecutor applicationExecutionExecutor(
            @Value("${application.execution.executor.core-pool-size:2}") int core,
            @Value("${application.execution.executor.max-pool-size:4}") int max,
            @Value("${application.execution.executor.queue-capacity:50}") int queue) {
        return build(core, max, queue, "app-exec-");
    }

    @Bean(name = BROWSER_AUTOMATION_EXECUTOR)
    public ThreadPoolTaskExecutor browserAutomationExecutor(
            @Value("${browser.automation.executor.core-pool-size:1}") int core,
            @Value("${browser.automation.executor.max-pool-size:2}") int max,
            @Value("${browser.automation.executor.queue-capacity:20}") int queue) {
        return build(core, max, queue, "browser-auto-");
    }

    @Bean(name = ATS_CONNECTOR_EXECUTOR)
    public ThreadPoolTaskExecutor atsConnectorExecutor(
            @Value("${ats.connector.executor.core-pool-size:2}") int core,
            @Value("${ats.connector.executor.max-pool-size:4}") int max,
            @Value("${ats.connector.executor.queue-capacity:50}") int queue) {
        return build(core, max, queue, "ats-connector-");
    }

    @Bean(name = TRACKING_EXECUTOR)
    public ThreadPoolTaskExecutor trackingExecutor(
            @Value("${application.tracking.executor.core-pool-size:2}") int core,
            @Value("${application.tracking.executor.max-pool-size:4}") int max,
            @Value("${application.tracking.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "app-tracking-");
    }

    @Bean(name = ANALYTICS_EXECUTOR)
    public ThreadPoolTaskExecutor analyticsExecutor(
            @Value("${application.analytics.executor.core-pool-size:1}") int core,
            @Value("${application.analytics.executor.max-pool-size:2}") int max,
            @Value("${application.analytics.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "app-analytics-");
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
