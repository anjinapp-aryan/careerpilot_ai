package ai.careerpilot.review.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Phase 7.12 — dedicated bounded executor for the AI Review Pipeline. Same shape as the other Phase 7
 * executor configs: bounded queue + default {@link java.util.concurrent.ThreadPoolExecutor.AbortPolicy},
 * so a saturated queue rejects immediately (the worker logs it) rather than growing an unbounded
 * backlog. Never a {@code SimpleAsyncTaskExecutor}.
 */
@Configuration
public class ReviewExecutorsConfig {

    public static final String APPLICATION_REVIEW_EXECUTOR = "applicationReviewExecutor";

    @Bean(name = APPLICATION_REVIEW_EXECUTOR)
    public ThreadPoolTaskExecutor applicationReviewExecutor(
            @Value("${application.review.executor.core-pool-size:2}") int core,
            @Value("${application.review.executor.max-pool-size:4}") int max,
            @Value("${application.review.executor.queue-capacity:200}") int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("app-review-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
