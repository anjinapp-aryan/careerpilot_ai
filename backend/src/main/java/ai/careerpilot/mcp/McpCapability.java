package ai.careerpilot.mcp;

/**
 * Phase 10.1 — the category of capability an {@link McpServerDefinition}/{@link McpToolDefinition}
 * exposes. Names the capability space, not a connection: no value below has a backing MCP
 * server wired in this phase (filesystem/database/browser/github servers are explicitly out of
 * scope — see the package javadoc).
 */
public enum McpCapability {
    FILESYSTEM,
    DATABASE,
    BROWSER,
    GITHUB,
    MEMORY,
    SEARCH,
    AUTOMATION,
    KNOWLEDGE
}
