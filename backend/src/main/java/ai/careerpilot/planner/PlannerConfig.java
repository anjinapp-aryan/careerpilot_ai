package ai.careerpilot.planner;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 11.2 — the only place any planner bean is constructed, gated by the single {@code
 * capability.planner.enabled} flag (default {@code false}, matching the phase spec exactly).
 * With it off, none of these beans exist and nothing outside {@code ai.careerpilot.planner}
 * references any of them — see the package javadoc for the "not wired into Copilot yet" scope
 * note (Phase 11.3's executor is the intended future caller).
 */
@Configuration
public class PlannerConfig {

    @Bean
    @ConditionalOnProperty(prefix = "capability.planner", name = "enabled", havingValue = "true")
    public CapabilityPlannerMetrics capabilityPlannerMetrics() {
        return new InMemoryCapabilityPlannerMetrics();
    }

    @Bean
    @ConditionalOnProperty(prefix = "capability.planner", name = "enabled", havingValue = "true")
    public PlanOptimizer planOptimizer(CapabilityPlannerMetrics metrics) {
        return new DefaultPlanOptimizer(metrics);
    }

    @Bean
    @ConditionalOnProperty(prefix = "capability.planner", name = "enabled", havingValue = "true")
    public CapabilityPlanner capabilityPlanner(PlanOptimizer optimizer, CapabilityPlannerMetrics metrics) {
        return new DefaultCapabilityPlanner(optimizer, metrics);
    }
}
