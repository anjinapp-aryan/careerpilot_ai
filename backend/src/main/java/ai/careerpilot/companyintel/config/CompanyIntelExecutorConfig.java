package ai.careerpilot.companyintel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Phase 7.13 — dedicated bounded executor for company-knowledge ingestion, deliberately not shared
 * with any pipeline stage (same isolation convention as {@code PipelineExecutorsConfig}): a
 * saturated knowledge queue can never starve tailoring/review work, and vice versa.
 */
@Configuration
public class CompanyIntelExecutorConfig {

    public static final String COMPANY_INTEL_EXECUTOR = "companyIntelExecutor";

    @Bean(COMPANY_INTEL_EXECUTOR)
    public ThreadPoolTaskExecutor companyIntelExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("company-intel-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
