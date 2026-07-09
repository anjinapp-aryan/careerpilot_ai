package ai.careerpilot.packageintel.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Phase 7.11 — dedicated bounded executor for asynchronous package validation. Same shape as
 * {@code AutopilotExecutorsConfig}/{@code WorkflowExecutorsConfig}: bounded queue + default
 * {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy}, so a saturated queue rejects immediately
 * (the worker logs it) rather than growing an unbounded backlog. Never a {@code SimpleAsyncTaskExecutor}.
 */
@Configuration
public class PackageIntelligenceExecutorsConfig {

    public static final String PACKAGE_INTELLIGENCE_EXECUTOR = "packageIntelligenceExecutor";

    @Bean(name = PACKAGE_INTELLIGENCE_EXECUTOR)
    public ThreadPoolTaskExecutor packageIntelligenceExecutor(
            @Value("${application.package.validation.executor.core-pool-size:2}") int core,
            @Value("${application.package.validation.executor.max-pool-size:4}") int max,
            @Value("${application.package.validation.executor.queue-capacity:200}") int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("pkg-intel-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
