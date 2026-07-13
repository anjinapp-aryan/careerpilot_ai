package ai.careerpilot.story.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Phase 7.15 — dedicated bounded executor for STAR story extraction/generation work, deliberately
 * not shared with any other pipeline stage (same isolation convention as
 * {@code CompanyIntelExecutorConfig}/{@code PipelineExecutorsConfig}).
 */
@Configuration
public class StoryExecutorConfig {

    public static final String STORY_EXECUTOR = "storyExecutor";

    @Bean(STORY_EXECUTOR)
    public ThreadPoolTaskExecutor storyExecutor(
            @Value("${story.executor.core-pool-size:2}") int corePoolSize,
            @Value("${story.executor.max-pool-size:4}") int maxPoolSize,
            @Value("${story.executor.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("story-intel-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
