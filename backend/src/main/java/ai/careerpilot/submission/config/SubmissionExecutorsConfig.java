package ai.careerpilot.submission.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Phase 7.16 — one dedicated bounded executor for the submission-session orchestration, mirroring
 * {@code PipelineExecutorsConfig}'s {@code build(core,max,queue,prefix)} shape. Deliberately not
 * shared with any other pipeline's executor so a saturated submission queue can't starve/be
 * starved by another stage.
 */
@Configuration
public class SubmissionExecutorsConfig {

    public static final String APPLICATION_SUBMISSION_EXECUTOR = "applicationSubmissionExecutor";

    @Bean(name = APPLICATION_SUBMISSION_EXECUTOR)
    public ThreadPoolTaskExecutor applicationSubmissionExecutor(
            @Value("${application.submission.executor.core-pool-size:2}") int core,
            @Value("${application.submission.executor.max-pool-size:4}") int max,
            @Value("${application.submission.executor.queue-capacity:50}") int queue) {
        return build(core, max, queue, "app-submission-");
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
