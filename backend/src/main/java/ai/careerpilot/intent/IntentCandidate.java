package ai.careerpilot.intent;

/**
 * Phase 11.1 — one scored candidate produced by {@link IntentResolver#resolve}. {@link
 * IntentClassifier} turns a ranked list of these into a single {@link IntentResult}; keeping
 * every candidate (not just the winner) is what satisfies the phase spec's "multiple candidate
 * intents" requirement — a future caller can inspect runner-up intents, not just the top pick.
 */
public record IntentCandidate(IntentType type, double score) {
}
