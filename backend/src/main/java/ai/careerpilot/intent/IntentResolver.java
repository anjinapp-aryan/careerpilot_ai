package ai.careerpilot.intent;

import java.util.List;

/**
 * Phase 11.1 — produces raw scored {@link IntentCandidate}s for a free-text message, one per
 * intent that matched at all. This is the deliberate ML-swap seam: {@link
 * KeywordIntentResolver} is a rule-based implementation today, but any future embedding/LLM-based
 * classifier plugs in behind this same interface — neither {@link IntentClassifier} nor {@link
 * IntentEngine} needs to change when that happens.
 */
public interface IntentResolver {

    List<IntentCandidate> resolve(String message);
}
