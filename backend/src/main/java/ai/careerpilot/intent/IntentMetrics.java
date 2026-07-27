package ai.careerpilot.intent;

/**
 * Phase 11.1 — observability contract for the Intent Engine: intent latency, confidence
 * distribution, selected-intent counts, and fallback counts — matching the phase spec's
 * Observability section ("Intent latency", "Intent confidence").
 */
public interface IntentMetrics {

    void recordIntentSelected(String intentType);

    void recordIntentLatency(long latencyMs);

    void recordConfidence(double score);

    void recordFallback(String reason);
}
