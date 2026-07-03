package ai.careerpilot.resumetailoring.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Phase 2D.1.1 — a dedicated, bounded executor for Resume Tailoring's async job pipeline
 * ({@code ResumeTailoringJobService}). Scoped to this feature only: the app-wide {@code @Async}
 * default ({@code SimpleAsyncTaskExecutor}, unbounded) is left untouched for every other listener
 * (e.g. {@code CandidateProfileEventListener}, {@code ResumeEmbeddingListener}) — smallest
 * possible blast radius for this hardening pass.
 *
 * <p>Pool size is intentionally small: each task holds an LLM call for up to ~2 minutes (see the
 * Phase 2D.1 live sign-off's P95 latency finding), so {@code maxPoolSize} in-flight tasks is that
 * many concurrent provider calls. A full queue rejects (default {@link
 * java.util.concurrent.ThreadPoolExecutor.AbortPolicy}) rather than growing unbounded;
 * {@code ResumeTailoringJobService} catches the rejection and marks the job {@code FAILED}
 * immediately instead of piling up threads.
 */
@Configuration
public class ResumeTailoringAsyncConfig {

    public static final String EXECUTOR_BEAN_NAME = "resumeTailoringExecutor";

    @Bean(name = EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor resumeTailoringExecutor(
            @Value("${resume.tailoring.executor.core-pool-size:2}") int corePoolSize,
            @Value("${resume.tailoring.executor.max-pool-size:5}") int maxPoolSize,
            @Value("${resume.tailoring.executor.queue-capacity:50}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("resume-tailor-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
