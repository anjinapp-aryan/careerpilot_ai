package ai.careerpilot.memory.enterprise;

/**
 * Phase 11.4 — the memory taxonomy named in the phase spec. {@code WORKING} is the default
 * landing type for anything freshly {@code remember}ed without an explicit hint — {@link
 * MemoryConsolidator} is what promotes a working entry into a more durable/specific type over
 * time, mirroring how short-term memory consolidates into long-term memory.
 */
public enum MemoryType {
    WORKING,
    LONG_TERM,
    CAREER,
    DECISION,
    CONVERSATION,
    SKILL,
    PREFERENCE,
    LEARNING
}
