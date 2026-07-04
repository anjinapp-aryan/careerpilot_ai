package ai.careerpilot.resumetailoring.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Phase 2D.3–2D.7 — five dedicated, bounded executors, one per pipeline stage (Gap Analysis, ATS
 * Explainability, Cover Letter, Application Package, Auto Apply Preparation). NO shared pools:
 * each stage's queue/health signals stay independent, exactly like {@code resumeTailoringExecutor}
 * and {@code atsOptimizationExecutor} before them. All use the default {@link
 * java.util.concurrent.ThreadPoolExecutor.AbortPolicy} — a full queue rejects immediately (the
 * worker catches and logs; the stage is skipped, never silently backlogged unbounded).
 *
 * <p>Pool sizes are configurable per stage via {@code <stage>.executor.*} properties; the
 * defaults are small on purpose. Only Cover Letter holds an LLM call (up to ~2 min under provider
 * degradation); the other four stages are deterministic DB/CPU work and finish in milliseconds.
 */
@Configuration
public class PipelineExecutorsConfig {

    public static final String GAP_ANALYSIS_EXECUTOR = "gapAnalysisExecutor";
    public static final String ATS_EXPLAINABILITY_EXECUTOR = "atsExplainabilityExecutor";
    public static final String COVER_LETTER_EXECUTOR = "coverLetterExecutor";
    public static final String APPLICATION_PACKAGE_EXECUTOR = "applicationPackageExecutor";
    public static final String AUTO_APPLY_PREPARATION_EXECUTOR = "autoApplyPreparationExecutor";

    @Bean(name = GAP_ANALYSIS_EXECUTOR)
    public ThreadPoolTaskExecutor gapAnalysisExecutor(
            @Value("${gap.analysis.executor.core-pool-size:2}") int core,
            @Value("${gap.analysis.executor.max-pool-size:4}") int max,
            @Value("${gap.analysis.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "gap-analysis-");
    }

    @Bean(name = ATS_EXPLAINABILITY_EXECUTOR)
    public ThreadPoolTaskExecutor atsExplainabilityExecutor(
            @Value("${ats.explainability.executor.core-pool-size:2}") int core,
            @Value("${ats.explainability.executor.max-pool-size:4}") int max,
            @Value("${ats.explainability.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "ats-explain-");
    }

    @Bean(name = COVER_LETTER_EXECUTOR)
    public ThreadPoolTaskExecutor coverLetterExecutor(
            @Value("${cover.letter.executor.core-pool-size:2}") int core,
            @Value("${cover.letter.executor.max-pool-size:5}") int max,
            @Value("${cover.letter.executor.queue-capacity:50}") int queue) {
        return build(core, max, queue, "cover-letter-");
    }

    @Bean(name = APPLICATION_PACKAGE_EXECUTOR)
    public ThreadPoolTaskExecutor applicationPackageExecutor(
            @Value("${application.package.executor.core-pool-size:2}") int core,
            @Value("${application.package.executor.max-pool-size:4}") int max,
            @Value("${application.package.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "app-package-");
    }

    @Bean(name = AUTO_APPLY_PREPARATION_EXECUTOR)
    public ThreadPoolTaskExecutor autoApplyPreparationExecutor(
            @Value("${auto.apply.executor.core-pool-size:2}") int core,
            @Value("${auto.apply.executor.max-pool-size:4}") int max,
            @Value("${auto.apply.executor.queue-capacity:100}") int queue) {
        return build(core, max, queue, "auto-apply-");
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
