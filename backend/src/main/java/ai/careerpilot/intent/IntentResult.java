package ai.careerpilot.intent;

import java.util.List;

/**
 * Phase 11.1 — the {@link IntentEngine}'s verdict for one message. {@code intentType} is {@code
 * null} when nothing resolved or classification confidence fell below the minimum threshold —
 * callers treat that identically to an explicit "fallback" (matching the same null-means-general
 * convention as {@code ai.careerpilot.capability.CapabilityDecision}).
 *
 * @param intentType the classified intent, or {@code null} for no-match/low-confidence
 * @param confidence the classification confidence for {@code intentType} (or {@link
 *                    IntentConfidence#zero()} when {@code intentType} is {@code null})
 * @param candidates every scored candidate, ranked highest-first, including the winner
 * @param reason     human-readable explanation, always populated
 */
public record IntentResult(IntentType intentType, IntentConfidence confidence,
                            List<IntentCandidate> candidates, String reason) {

    public static IntentResult none(String reason) {
        return new IntentResult(null, IntentConfidence.zero(), List.of(), reason);
    }
}
