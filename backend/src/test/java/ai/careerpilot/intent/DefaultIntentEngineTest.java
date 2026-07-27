package ai.careerpilot.intent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultIntentEngine} — every break in the chain (no candidates, below-threshold
 * confidence, an exception) must produce an explicit {@code intentType=null} fallback rather
 * than throwing, and a successful classification must be recorded to both {@link IntentHistory}
 * and {@link IntentMetrics}.
 */
class DefaultIntentEngineTest {

    private final InMemoryIntentHistory history = new InMemoryIntentHistory();
    private final InMemoryIntentMetrics metrics = new InMemoryIntentMetrics();

    @Test
    void noCandidatesResolved_fallsBackWithReason() {
        IntentResolver resolver = message -> List.of();
        IntentClassifier classifier = candidates -> List.of();
        DefaultIntentEngine engine = new DefaultIntentEngine(resolver, classifier, history, metrics, 0.4);

        IntentResult result = engine.analyze(UUID.randomUUID(), "irrelevant");

        assertThat(result.intentType()).isNull();
        assertThat(result.reason()).contains("no candidates resolved");
    }

    @Test
    void belowThresholdConfidence_fallsBackButKeepsCandidatesVisible() {
        IntentResolver resolver = message -> List.of(new IntentCandidate(IntentType.LEARNING_HELP, 0.2));
        IntentClassifier classifier = candidates -> candidates;
        DefaultIntentEngine engine = new DefaultIntentEngine(resolver, classifier, history, metrics, 0.4);

        IntentResult result = engine.analyze(UUID.randomUUID(), "irrelevant");

        assertThat(result.intentType()).isNull();
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.reason()).contains("below minConfidence");
    }

    @Test
    void aboveThresholdConfidence_returnsClassifiedIntent_andRecordsMetricsAndHistory() {
        IntentResolver resolver = message -> List.of(new IntentCandidate(IntentType.GITHUB_ANALYSIS, 0.9));
        IntentClassifier classifier = candidates -> candidates;
        DefaultIntentEngine engine = new DefaultIntentEngine(resolver, classifier, history, metrics, 0.4);
        UUID userId = UUID.randomUUID();

        IntentResult result = engine.analyze(userId, "review my github");

        assertThat(result.intentType()).isEqualTo(IntentType.GITHUB_ANALYSIS);
        assertThat(result.confidence().level()).isEqualTo(IntentConfidence.Level.HIGH);
        assertThat(metrics.selectionCount("GITHUB_ANALYSIS")).isEqualTo(1);
        assertThat(history.recentFor(userId, 5)).hasSize(1);
    }

    @Test
    void resolverThrows_fallsBackRatherThanPropagating() {
        IntentResolver resolver = message -> { throw new RuntimeException("boom"); };
        IntentClassifier classifier = candidates -> candidates;
        DefaultIntentEngine engine = new DefaultIntentEngine(resolver, classifier, history, metrics, 0.4);

        IntentResult result = engine.analyze(UUID.randomUUID(), "irrelevant");

        assertThat(result.intentType()).isNull();
        assertThat(result.reason()).contains("classification failed");
    }

    @Test
    void nullUserId_doesNotRecordHistoryButStillClassifies() {
        IntentResolver resolver = message -> List.of(new IntentCandidate(IntentType.GITHUB_ANALYSIS, 0.9));
        IntentClassifier classifier = candidates -> candidates;
        DefaultIntentEngine engine = new DefaultIntentEngine(resolver, classifier, history, metrics, 0.4);

        IntentResult result = engine.analyze(null, "review my github");

        assertThat(result.intentType()).isEqualTo(IntentType.GITHUB_ANALYSIS);
    }
}
