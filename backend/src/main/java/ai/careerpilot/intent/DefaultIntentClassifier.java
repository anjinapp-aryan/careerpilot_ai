package ai.careerpilot.intent;

import java.util.Comparator;
import java.util.List;

/**
 * Phase 11.1 — the default {@link IntentClassifier}. Ranks candidates by score descending; ties
 * (equal score) are broken by the matching {@link IntentDefinition#priority()}, higher first —
 * this is the "Intent priority" requirement from the phase spec, e.g. a message matching both
 * GitHub-review and career-strategy keywords equally should still prefer the more specific
 * GITHUB_ANALYSIS (priority 100) over CAREER_STRATEGY (priority 80).
 */
public class DefaultIntentClassifier implements IntentClassifier {

    private final IntentRegistry registry;

    public DefaultIntentClassifier(IntentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<IntentCandidate> classify(List<IntentCandidate> rawCandidates) {
        return rawCandidates.stream()
                .sorted(Comparator
                        .comparingDouble(IntentCandidate::score).reversed()
                        .thenComparing(this::priorityOf, Comparator.reverseOrder()))
                .toList();
    }

    private int priorityOf(IntentCandidate candidate) {
        return registry.find(candidate.type()).map(IntentDefinition::priority).orElse(0);
    }
}
