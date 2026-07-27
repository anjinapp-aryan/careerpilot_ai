package ai.careerpilot.memory.enterprise;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Phase 11.4 — the only place any enterprise-memory bean is constructed, gated by the single
 * {@code enterprise.memory.enabled} flag (default {@code false}, matching every prior Phase 11
 * sub-phase's single-flag simplicity). With it off, none of these beans exist and nothing
 * outside {@code ai.careerpilot.memory.enterprise} references any of them — see the package
 * javadoc for the "additive, not a replacement" scope note.
 */
@Configuration
public class EnterpriseMemoryConfig {

    @Bean
    @ConditionalOnProperty(prefix = "enterprise.memory", name = "enabled", havingValue = "true")
    public InMemoryMemoryStore memoryStore() {
        return new InMemoryMemoryStore();
    }

    @Bean
    @ConditionalOnProperty(prefix = "enterprise.memory", name = "enabled", havingValue = "true")
    public MemoryClassifier memoryClassifier() {
        return new DefaultMemoryClassifier();
    }

    @Bean
    @ConditionalOnProperty(prefix = "enterprise.memory", name = "enabled", havingValue = "true")
    public MemoryMetrics memoryMetrics() {
        return new InMemoryMemoryMetrics();
    }

    @Bean
    @ConditionalOnProperty(prefix = "enterprise.memory", name = "enabled", havingValue = "true")
    public MemoryPolicy memoryPolicy(
            @Value("${enterprise.memory.working-ttl-hours:24}") long workingTtlHours,
            @Value("${enterprise.memory.promotion-access-threshold:3}") int promotionAccessThreshold,
            @Value("${enterprise.memory.max-entries-per-type:200}") int maxEntriesPerType) {
        return new MemoryPolicy(Duration.ofHours(workingTtlHours), promotionAccessThreshold, maxEntriesPerType);
    }

    @Bean
    @ConditionalOnProperty(prefix = "enterprise.memory", name = "enabled", havingValue = "true")
    public MemoryManager memoryManager(InMemoryMemoryStore store, MemoryClassifier classifier, MemoryPolicy policy, MemoryMetrics metrics) {
        return new DefaultMemoryManager(store, classifier, policy, metrics);
    }

    @Bean
    @ConditionalOnProperty(prefix = "enterprise.memory", name = "enabled", havingValue = "true")
    public MemoryRetriever memoryRetriever(InMemoryMemoryStore store, MemoryMetrics metrics) {
        return new DefaultMemoryRetriever(store, metrics);
    }

    @Bean
    @ConditionalOnProperty(prefix = "enterprise.memory", name = "enabled", havingValue = "true")
    public MemorySearch memorySearch(InMemoryMemoryStore store, MemoryMetrics metrics) {
        return new DefaultMemorySearch(store, metrics);
    }

    @Bean
    @ConditionalOnProperty(prefix = "enterprise.memory", name = "enabled", havingValue = "true")
    public MemoryConsolidator memoryConsolidator(InMemoryMemoryStore store, MemoryClassifier classifier, MemoryPolicy policy, MemoryMetrics metrics) {
        return new DefaultMemoryConsolidator(store, classifier, policy, metrics);
    }
}
