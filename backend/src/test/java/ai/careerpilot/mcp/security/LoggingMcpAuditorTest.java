package ai.careerpilot.mcp.security;

import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.McpToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link LoggingMcpAuditor} — logs only, never throws regardless of input, including a null
 * context (matches {@code ai.careerpilot.mcp.tool.DefaultMcpExecutor}'s own try/catch
 * belt-and-suspenders around the audit call, but the auditor itself should already be safe on
 * its own).
 */
class LoggingMcpAuditorTest {

    private final LoggingMcpAuditor auditor = new LoggingMcpAuditor();
    private final McpToolDefinition tool = new McpToolDefinition("t", "d", Map.of(), Map.of(), McpCapability.DATABASE, "s");

    @Test
    void recordDoesNotThrowForSuccessfulResult() {
        McpExecutionContext context = new McpExecutionContext(UUID.randomUUID(), null, null, "trace", Duration.ofSeconds(1), Map.of());
        assertThatCode(() -> auditor.record(tool, context, McpToolResult.ok("fine"))).doesNotThrowAnyException();
    }

    @Test
    void recordDoesNotThrowForFailedResultOrNullContext() {
        assertThatCode(() -> auditor.record(tool, null, McpToolResult.failed("boom"))).doesNotThrowAnyException();
    }
}
