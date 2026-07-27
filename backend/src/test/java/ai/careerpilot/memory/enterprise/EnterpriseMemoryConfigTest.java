package ai.careerpilot.memory.enterprise;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EnterpriseMemoryConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EnterpriseMemoryConfig.class);

    @Test
    void withFlagAtDefault_noMemoryBeansAreConstructed() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(InMemoryMemoryStore.class);
            assertThat(context).doesNotHaveBean(MemoryClassifier.class);
            assertThat(context).doesNotHaveBean(MemoryManager.class);
            assertThat(context).doesNotHaveBean(MemoryRetriever.class);
            assertThat(context).doesNotHaveBean(MemorySearch.class);
            assertThat(context).doesNotHaveBean(MemoryConsolidator.class);
            assertThat(context).doesNotHaveBean(MemoryMetrics.class);
        });
    }

    @Test
    void withFlagOn_allBeansConstructed() {
        contextRunner.withPropertyValues("enterprise.memory.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(InMemoryMemoryStore.class);
            assertThat(context).hasSingleBean(MemoryManager.class);
            assertThat(context).hasSingleBean(MemoryRetriever.class);
            assertThat(context).hasSingleBean(MemorySearch.class);
            assertThat(context).hasSingleBean(MemoryConsolidator.class);
        });
    }

    @Test
    void endToEnd_wiredManagerAndRetrieverWorkTogether() {
        contextRunner.withPropertyValues("enterprise.memory.enabled=true").run(context -> {
            MemoryManager manager = context.getBean(MemoryManager.class);
            MemoryRetriever retriever = context.getBean(MemoryRetriever.class);
            UUID userId = UUID.randomUUID();

            manager.remember(userId, "I decided to accept the offer", null);
            var results = retriever.retrieve(userId, MemoryType.DECISION, 10);

            assertThat(results).hasSize(1);
        });
    }

    @Test
    void customPolicyPropertiesAreBound() {
        contextRunner.withPropertyValues(
                "enterprise.memory.enabled=true",
                "enterprise.memory.working-ttl-hours=48",
                "enterprise.memory.promotion-access-threshold=5",
                "enterprise.memory.max-entries-per-type=10"
        ).run(context -> {
            MemoryPolicy policy = context.getBean(MemoryPolicy.class);
            assertThat(policy.workingMemoryTtl()).isEqualTo(java.time.Duration.ofHours(48));
            assertThat(policy.promotionAccessThreshold()).isEqualTo(5);
            assertThat(policy.maxEntriesPerType()).isEqualTo(10);
        });
    }
}
