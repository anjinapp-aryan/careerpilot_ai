package ai.careerpilot.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link McpToolResult} factory helpers — a failed call is a normal value, never an exception. */
class McpToolResultTest {

    @Test
    void okCarriesOutputAndNoError() {
        McpToolResult result = McpToolResult.ok("payload");

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("payload");
        assertThat(result.error()).isNull();
    }

    @Test
    void failedCarriesErrorAndNoOutput() {
        McpToolResult result = McpToolResult.failed("timed out");

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isNull();
        assertThat(result.error()).isEqualTo("timed out");
    }
}
