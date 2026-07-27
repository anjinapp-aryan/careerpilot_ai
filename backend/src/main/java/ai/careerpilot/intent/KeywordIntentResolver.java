package ai.careerpilot.intent;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 11.1 — the default {@link IntentResolver}: scores each registered {@link
 * IntentDefinition} by how many of its keyword fragments appear in the (lower-cased) message.
 * Score is {@code min(1.0, matchedKeywordCount * 0.5)} — one match alone yields MEDIUM
 * confidence (0.5), two or more yields HIGH (1.0) — deliberately conservative so a single
 * ambiguous word doesn't produce false HIGH confidence. Only intents with at least one match are
 * returned (no zero-score noise).
 */
public class KeywordIntentResolver implements IntentResolver {

    private final IntentRegistry registry;

    public KeywordIntentResolver(IntentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<IntentCandidate> resolve(String message) {
        List<IntentCandidate> candidates = new ArrayList<>();
        if (message == null || message.isBlank()) {
            return candidates;
        }
        String lower = message.toLowerCase();
        for (IntentDefinition definition : registry.all()) {
            long matched = definition.keywords().stream().filter(lower::contains).count();
            if (matched > 0) {
                double score = Math.min(1.0, matched * 0.5);
                candidates.add(new IntentCandidate(definition.type(), score));
            }
        }
        return candidates;
    }
}
