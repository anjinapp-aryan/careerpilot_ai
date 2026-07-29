package ai.careerpilot.missionexecution;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Pre-Phase-9 Hardening — the only place any Mission Execution Engine bean is constructed, gated
 * by the single {@code execution.engine.enabled} flag (default {@code false}). Every bean here is
 * stateless (bar the in-memory {@link InMemoryExecutionHistory}) and side-effect free — flipping
 * this flag on changes nothing on any request path, since nothing outside this package calls
 * {@link MissionExecutionEngine} yet.
 */
@Configuration
public class MissionExecutionEngineConfig {

    @Bean
    @ConditionalOnProperty(prefix = "execution.engine", name = "enabled", havingValue = "true")
    public ExecutionPriorityResolver executionPriorityResolver() {
        return new DefaultExecutionPriorityResolver();
    }

    @Bean
    @ConditionalOnProperty(prefix = "execution.engine", name = "enabled", havingValue = "true")
    public ExecutionDependencyResolver executionDependencyResolver() {
        return new DefaultExecutionDependencyResolver();
    }

    @Bean
    @ConditionalOnProperty(prefix = "execution.engine", name = "enabled", havingValue = "true")
    public ExecutionScheduler executionScheduler() {
        return new DefaultExecutionScheduler();
    }

    @Bean
    @ConditionalOnProperty(prefix = "execution.engine", name = "enabled", havingValue = "true")
    public ExecutionEstimator executionEstimator() {
        return new DefaultExecutionEstimator();
    }

    @Bean
    @ConditionalOnProperty(prefix = "execution.engine", name = "enabled", havingValue = "true")
    public ExecutionValidator executionValidator() {
        return new DefaultExecutionValidator();
    }

    @Bean
    @ConditionalOnProperty(prefix = "execution.engine", name = "enabled", havingValue = "true")
    public ExecutionHistory executionHistory() {
        return new InMemoryExecutionHistory();
    }

    @Bean
    @ConditionalOnProperty(prefix = "execution.engine", name = "enabled", havingValue = "true")
    public MissionExecutionEngine missionExecutionEngine(ExecutionPriorityResolver priorityResolver,
                                                           ExecutionDependencyResolver dependencyResolver,
                                                           ExecutionScheduler scheduler, ExecutionEstimator estimator,
                                                           ExecutionValidator validator, ExecutionHistory history) {
        return new DefaultMissionExecutionEngine(priorityResolver, dependencyResolver, scheduler, estimator, validator, history);
    }
}
