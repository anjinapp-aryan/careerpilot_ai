package ai.careerpilot.memory.enterprise;

/**
 * Phase 11.4 — an importance score in {@code [0.0, 1.0]} plus a derived qualitative band, same
 * shape as {@code ai.careerpilot.intent.IntentConfidence}. Banding: {@code >= 0.85} CRITICAL,
 * {@code >= 0.6} HIGH, {@code >= 0.35} MEDIUM, else LOW.
 */
public record MemoryImportance(double score) {

    public MemoryImportance {
        score = Math.max(0.0, Math.min(1.0, score));
    }

    public enum Level { CRITICAL, HIGH, MEDIUM, LOW }

    public Level level() {
        if (score >= 0.85) return Level.CRITICAL;
        if (score >= 0.6) return Level.HIGH;
        if (score >= 0.35) return Level.MEDIUM;
        return Level.LOW;
    }
}
