package ai.careerpilot.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultIntentClassifierTest {

    private final IntentRegistry registry = new InMemoryIntentRegistry();
    private final DefaultIntentClassifier classifier = new DefaultIntentClassifier(registry);

    @Test
    void ranksHigherScoreFirst() {
        List<IntentCandidate> raw = List.of(
                new IntentCandidate(IntentType.LEARNING_HELP, 0.5),
                new IntentCandidate(IntentType.GITHUB_ANALYSIS, 1.0));

        List<IntentCandidate> ranked = classifier.classify(raw);

        assertThat(ranked.get(0).type()).isEqualTo(IntentType.GITHUB_ANALYSIS);
        assertThat(ranked.get(1).type()).isEqualTo(IntentType.LEARNING_HELP);
    }

    @Test
    void tiedScoresBrokenByDefinitionPriority_higherPriorityWins() {
        // GITHUB_ANALYSIS (priority 100) vs LEARNING_HELP (priority 60), equal score
        List<IntentCandidate> raw = List.of(
                new IntentCandidate(IntentType.LEARNING_HELP, 0.5),
                new IntentCandidate(IntentType.GITHUB_ANALYSIS, 0.5));

        List<IntentCandidate> ranked = classifier.classify(raw);

        assertThat(ranked.get(0).type()).isEqualTo(IntentType.GITHUB_ANALYSIS);
    }

    @Test
    void emptyInputProducesEmptyOutput() {
        assertThat(classifier.classify(List.of())).isEmpty();
    }
}
