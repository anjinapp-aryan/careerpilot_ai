package ai.careerpilot.mcp;

/**
 * Phase 10.1 — the outcome shape a future {@link McpExecutor} implementation would return.
 * Modeled as a plain success/output/error triple (not an exception-based contract) so a
 * failed tool call is a normal value a caller can branch on, matching the fail-safe,
 * never-throw-to-the-caller convention already used throughout this codebase (e.g. job
 * discovery providers, resume-tailoring pipeline workers — see CLAUDE.md).
 *
 * @param success whether the tool call completed without error
 * @param output  the tool's result payload when {@code success} is {@code true}; otherwise {@code null}
 * @param error   a human-readable failure reason when {@code success} is {@code false}; otherwise {@code null}
 */
public record McpToolResult(boolean success, Object output, String error) {

    public static McpToolResult ok(Object output) {
        return new McpToolResult(true, output, null);
    }

    public static McpToolResult failed(String error) {
        return new McpToolResult(false, null, error);
    }
}
