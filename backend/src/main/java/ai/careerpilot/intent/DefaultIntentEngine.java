package ai.careerpilot.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Phase 11.1 — the default {@link IntentEngine}. Chain: {@link IntentResolver#resolve} (raw
 * scores) → {@link IntentClassifier#classify} (ranked, priority-tie-broken) → accept the top
 * candidate only if its score meets {@code minConfidence}; otherwise fall back to {@code
 * IntentResult.none(...)}. Every path — no candidates, below-threshold confidence, or an
 * exception anywhere in the chain — produces an explicit, reasoned fallback rather than
 * propagating or guessing; matches the same never-fail discipline as {@code
 * ai.careerpilot.capability.DefaultCapabilityEngine}.
 */
public class DefaultIntentEngine implements IntentEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultIntentEngine.class);

    private final IntentResolver resolver;
    private final IntentClassifier classifier;
    private final IntentHistory history;
    private final IntentMetrics metrics;
    private final double minConfidence;

    public DefaultIntentEngine(IntentResolver resolver, IntentClassifier classifier,
                                IntentHistory history, IntentMetrics metrics, double minConfidence) {
        this.resolver = resolver;
        this.classifier = classifier;
        this.history = history;
        this.metrics = metrics;
        this.minConfidence = minConfidence;
    }

    @Override
    public IntentResult analyze(UUID userId, String message) {
        long start = System.currentTimeMillis();
        IntentResult result = doAnalyze(message);
        metrics.recordIntentLatency(System.currentTimeMillis() - start);
        if (userId != null) {
            history.record(userId, result);
        }
        return result;
    }

    private IntentResult doAnalyze(String message) {
        try {
            List<IntentCandidate> raw = resolver.resolve(message);
            if (raw.isEmpty()) {
                metrics.recordFallback("no candidates resolved");
                return IntentResult.none("no candidates resolved");
            }

            List<IntentCandidate> ranked = classifier.classify(raw);
            IntentCandidate top = ranked.get(0);

            if (top.score() < minConfidence) {
                metrics.recordFallback("confidence below threshold");
                return new IntentResult(null, new IntentConfidence(top.score()), ranked,
                        "top candidate " + top.type() + " below minConfidence=" + minConfidence);
            }

            IntentConfidence confidence = new IntentConfidence(top.score());
            metrics.recordIntentSelected(top.type().name());
            metrics.recordConfidence(confidence.score());
            return new IntentResult(top.type(), confidence, ranked, "classified as " + top.type());
        } catch (Exception e) {
            log.warn("Intent classification failed, falling back: {}", e.toString());
            metrics.recordFallback("exception: " + e);
            return IntentResult.none("classification failed: " + e);
        }
    }
}
