package ai.careerpilot.intent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryIntentRegistryTest {

    private final InMemoryIntentRegistry registry = new InMemoryIntentRegistry();

    @Test
    void allSevenIntentTypesArePreRegistered() {
        assertThat(registry.all()).hasSize(7);
        for (IntentType type : IntentType.values()) {
            assertThat(registry.find(type)).isPresent();
        }
    }

    @Test
    void githubAnalysisHasHighestPriority() {
        IntentDefinition github = registry.find(IntentType.GITHUB_ANALYSIS).orElseThrow();
        for (IntentDefinition other : registry.all()) {
            if (other.type() != IntentType.GITHUB_ANALYSIS) {
                assertThat(github.priority()).isGreaterThanOrEqualTo(other.priority());
            }
        }
    }
}
