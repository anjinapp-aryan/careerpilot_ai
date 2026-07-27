package ai.careerpilot.ai.springai.mcp;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 9.1 — extension-point only. The context a future MCP tool invocation would
 * carry (mirrors the {@code AuthenticatedUser} shape already used across every
 * controller — see {@code ai.careerpilot.security.AuthenticatedUser} — so a future
 * tool-execution layer can enforce the same per-user/per-org isolation convention
 * documented in CLAUDE.md, "JWT auth carries multi-tenant context"). No executor
 * consumes this type today.
 */
public record ToolExecutionContext(UUID userId, UUID orgId, Map<String, Object> attributes) {
}
