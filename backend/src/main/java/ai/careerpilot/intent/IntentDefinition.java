package ai.careerpilot.intent;

import java.util.Set;

/**
 * Phase 11.1 — metadata describing one {@link IntentType}: the phrase fragments a rule-based
 * {@link IntentResolver} matches against, and a priority used to break near-tied confidence
 * scores (higher wins — see {@link DefaultIntentClassifier}). Pure data, mirroring {@code
 * ai.careerpilot.capability.CapabilityDefinition}'s shape.
 *
 * @param type      the intent this definition describes
 * @param description human-readable, for logging/diagnostics
 * @param priority  tie-break weight when two intents score equally (higher wins)
 * @param keywords  lower-case phrase fragments a keyword-based resolver matches via {@code contains}
 */
public record IntentDefinition(IntentType type, String description, int priority, Set<String> keywords) {
}
