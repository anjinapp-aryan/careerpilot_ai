package ai.careerpilot.resumetailoring.ats;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Phase 2D.2 — a dedicated, bounded executor for the ATS Optimization async job pipeline
 * ({@code AtsOptimizationJobService}), separate from {@code resumeTailoringExecutor}
 * ({@code ai.careerpilot.resumetailoring.config.ResumeTailoringAsyncConfig}) so each feature's
 * queue/health signals stay independent — same "smallest blast radius" reasoning applied when
 * Resume Tailoring's own bounded executor was added in 2D.1.1.
 */
@Configuration
public class AtsOptimizationAsyncConfig {

    public static final String EXECUTOR_BEAN_NAME = "atsOptimizationExecutor";

    @Bean(name = EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor atsOptimizationExecutor(
            @Value("${ats.optimization.executor.core-pool-size:2}") int corePoolSize,
            @Value("${ats.optimization.executor.max-pool-size:5}") int maxPoolSize,
            @Value("${ats.optimization.executor.queue-capacity:50}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ats-optimize-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
