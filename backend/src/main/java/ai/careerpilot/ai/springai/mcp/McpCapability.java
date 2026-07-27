package ai.careerpilot.ai.springai.mcp;

/**
 * Phase 9.1 — extension-point only. Names the category of an {@link McpToolDefinition}
 * a future MCP tool server would expose. No implementation exists for any value
 * below; adding filesystem/Postgres/GitHub tool servers is explicitly Phase 9.3+
 * scope, not this phase.
 */
public enum McpCapability {
    FILESYSTEM_ACCESS,
    DATABASE_ACCESS,
    GIT_ACCESS,
    WEB_SEARCH,
    HTTP_REQUEST,
    CUSTOM
}
