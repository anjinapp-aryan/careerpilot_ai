package ai.careerpilot.autopilot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Phase 7.10 — dedicated bounded executor for the autonomous career orchestrator, one per-user run
 * dispatched onto it. Same shape as {@code WorkflowExecutorsConfig}/{@code LearningExecutorsConfig}:
 * bounded queue + default {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy}, so a saturated
 * queue rejects immediately (the scheduler logs it) rather than growing an unbounded backlog. Never a
 * {@code SimpleAsyncTaskExecutor}.
 */
@Configuration
public class AutopilotExecutorsConfig {

    public static final String AUTOPILOT_EXECUTOR = "autopilotExecutor";

    @Bean(name = AUTOPILOT_EXECUTOR)
    public ThreadPoolTaskExecutor autopilotExecutor(
            @Value("${career.orchestrator.executor.core-pool-size:2}") int core,
            @Value("${career.orchestrator.executor.max-pool-size:4}") int max,
            @Value("${career.orchestrator.executor.queue-capacity:200}") int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("autopilot-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
