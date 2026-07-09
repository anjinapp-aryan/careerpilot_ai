package ai.careerpilot.learning.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Phase 6 — 5 dedicated bounded executors, one per pipeline stage, deliberately not shared so one
 * stage's saturated queue can't starve another's. Exact same shape as
 * {@code ExecutionExecutorsConfig}/{@code PipelineExecutorsConfig}: bounded queue, {@link ThreadPoolExecutor.AbortPolicy}
 * (the default rejection policy on a plain {@code ThreadPoolTaskExecutor}), never a
 * {@code SimpleAsyncTaskExecutor}.
 */
@Configuration
public class LearningExecutorsConfig {

    public static final String LEARNING_EXECUTOR = "learningExecutor";
    public static final String SUCCESS_PATTERN_EXECUTOR = "successPatternExecutor";
    public static final String FAILURE_PATTERN_EXECUTOR = "failurePatternExecutor";
    public static final String RESUME_LEARNING_EXECUTOR = "resumeLearningExecutor";
    public static final String CAREER_LEARNING_EXECUTOR = "careerLearningExecutor";

    @Bean(name = LEARNING_EXECUTOR)
    public ThreadPoolTaskExecutor learningExecutor(
            @Value("${learning.executor.core-pool-size:2}") int core,
            @Value("${learning.executor.max-pool-size:4}") int max,
            @Value("${learning.executor.queue-capacity:200}") int queue) {
        return build(core, max, queue, "learning-");
    }

    @Bean(name = SUCCESS_PATTERN_EXECUTOR)
    public ThreadPoolTaskExecutor successPatternExecutor(
            @Value("${learning.success-pattern.executor.core-pool-size:2}") int core,
            @Value("${learning.success-pattern.executor.max-pool-size:4}") int max,
            @Value("${learning.success-pattern.executor.queue-capacity:200}") int queue) {
        return build(core, max, queue, "success-pattern-");
    }

    @Bean(name = FAILURE_PATTERN_EXECUTOR)
    public ThreadPoolTaskExecutor failurePatternExecutor(
            @Value("${learning.failure-pattern.executor.core-pool-size:2}") int core,
            @Value("${learning.failure-pattern.executor.max-pool-size:4}") int max,
            @Value("${learning.failure-pattern.executor.queue-capacity:200}") int queue) {
        return build(core, max, queue, "failure-pattern-");
    }

    @Bean(name = RESUME_LEARNING_EXECUTOR)
    public ThreadPoolTaskExecutor resumeLearningExecutor(
            @Value("${learning.adaptive-resume.executor.core-pool-size:1}") int core,
            @Value("${learning.adaptive-resume.executor.max-pool-size:2}") int max,
            @Value("${learning.adaptive-resume.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "resume-learning-");
    }

    @Bean(name = CAREER_LEARNING_EXECUTOR)
    public ThreadPoolTaskExecutor careerLearningExecutor(
            @Value("${learning.adaptive-career.executor.core-pool-size:1}") int core,
            @Value("${learning.adaptive-career.executor.max-pool-size:2}") int max,
            @Value("${learning.adaptive-career.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "career-learning-");
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
