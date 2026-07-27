package ai.careerpilot.intent;

import java.util.List;

/**
 * Phase 11.1 — turns a raw, unordered list of {@link IntentCandidate}s (from {@link
 * IntentResolver}) into a ranked, tie-broken decision. Separate from {@link IntentResolver} so
 * scoring (message-only, ML-swappable) and decision policy (priority tie-breaking, ranking) can
 * evolve independently.
 */
public interface IntentClassifier {

    /** Candidates ranked highest-confidence first; empty if none were provided. */
    List<IntentCandidate> classify(List<IntentCandidate> rawCandidates);
}
