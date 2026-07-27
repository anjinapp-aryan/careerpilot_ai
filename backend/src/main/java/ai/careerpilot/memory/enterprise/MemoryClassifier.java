package ai.careerpilot.memory.enterprise;

/**
 * Phase 11.4 — classifies free-text content into a {@link MemoryType} and scores its {@link
 * MemoryImportance}. Rule-based today ({@link DefaultMemoryClassifier}); the deliberate ML-swap
 * seam, same discipline as {@code ai.careerpilot.intent.IntentResolver}.
 */
public interface MemoryClassifier {

    MemoryType classify(String content);

    MemoryImportance scoreImportance(String content, MemoryType type);
}
