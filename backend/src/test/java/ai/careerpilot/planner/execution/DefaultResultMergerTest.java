package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.mcp.McpToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultResultMergerTest {

    private final DefaultResultMerger merger = new DefaultResultMerger();

    @Test
    void successfulResultsAppearInStructuredPerCapabilityMap() {
        ExecutionResult success = new ExecutionResult(CapabilityType.GITHUB_REVIEW,
                Map.of("analyze_github_profile", McpToolResult.ok(Map.of("repoCount", 5))), true, 1, 10, null);

        MergedExecutionContext merged = merger.merge(Map.of(CapabilityType.GITHUB_REVIEW, success));

        assertThat(merged.perCapability()).containsKey(CapabilityType.GITHUB_REVIEW);
        assertThat(merged.textBlock()).contains("GITHUB_REVIEW");
        assertThat(merged.textBlock()).contains("repoCount");
    }

    @Test
    void failedResultsAreNotedInTextButExcludedFromStructuredMap() {
        ExecutionResult failure = new ExecutionResult(CapabilityType.GITHUB_REVIEW, Map.of(), false, 3, 10, "network down");

        MergedExecutionContext merged = merger.merge(Map.of(CapabilityType.GITHUB_REVIEW, failure));

        assertThat(merged.perCapability()).doesNotContainKey(CapabilityType.GITHUB_REVIEW);
        assertThat(merged.textBlock()).contains("unavailable");
        assertThat(merged.textBlock()).contains("network down");
    }

    @Test
    void emptyResultsProduceEmptyMergedContext() {
        MergedExecutionContext merged = merger.merge(Map.of());
        assertThat(merged.perCapability()).isEmpty();
        assertThat(merged.textBlock()).isEmpty();
    }
}
