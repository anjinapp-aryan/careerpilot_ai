package ai.careerpilot.intent;

/**
 * Phase 11.1 — a classification confidence: a raw {@code score} in {@code [0.0, 1.0]} plus a
 * derived qualitative {@link Level} band a caller can branch on without hardcoding thresholds
 * twice. Banding: {@code >= 0.66} HIGH, {@code >= 0.33} MEDIUM, else LOW.
 */
public record IntentConfidence(double score) {

    public IntentConfidence {
        score = Math.max(0.0, Math.min(1.0, score));
    }

    public enum Level { HIGH, MEDIUM, LOW }

    public Level level() {
        if (score >= 0.66) return Level.HIGH;
        if (score >= 0.33) return Level.MEDIUM;
        return Level.LOW;
    }

    public static IntentConfidence zero() {
        return new IntentConfidence(0.0);
    }
}
