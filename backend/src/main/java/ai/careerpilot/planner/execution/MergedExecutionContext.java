package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityType;

import java.util.Map;

/**
 * Phase 11.3 — {@link ResultMerger}'s output: a structured object, not raw concatenated text
 * (per the phase spec's own context-merging requirement, already established in Phase 10.4's
 * {@code CapabilityAwareChatService.mergeResults}). {@code perCapability} keeps each
 * capability's data addressable on its own; {@code textBlock} is the same data rendered as a
 * single prompt-ready string for a future LLM synthesis step to consume.
 *
 * @param perCapability successful capabilities only, keyed by type, value is the raw merged tool output
 * @param textBlock     a formatted, human/LLM-readable rendering of every result (success and failure)
 */
public record MergedExecutionContext(Map<CapabilityType, Object> perCapability, String textBlock) {

    public static MergedExecutionContext empty() {
        return new MergedExecutionContext(Map.of(), "");
    }
}
