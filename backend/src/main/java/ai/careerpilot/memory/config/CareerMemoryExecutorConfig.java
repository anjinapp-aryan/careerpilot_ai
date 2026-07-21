package ai.careerpilot.memory.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * One small, dedicated, bounded executor for memory extraction — same shape as
 * {@code LearningExecutorsConfig}, deliberately not sharing the learning engine's executor so a
 * saturated memory-extraction queue can never starve pattern computation, or vice versa.
 */
@Configuration
public class CareerMemoryExecutorConfig {

    public static final String CAREER_MEMORY_EXECUTOR = "careerMemoryExecutor";

    @Bean(name = CAREER_MEMORY_EXECUTOR)
    public ThreadPoolTaskExecutor careerMemoryExecutor(
            @Value("${career.memory.executor.core-pool-size:1}") int core,
            @Value("${career.memory.executor.max-pool-size:2}") int max,
            @Value("${career.memory.executor.queue-capacity:100}") int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("career-memory-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
