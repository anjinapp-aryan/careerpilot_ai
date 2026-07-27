package ai.careerpilot.mcp.security;

import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpToolDefinition;

/**
 * Phase 10.2 — the first real {@link McpAuthorizationProvider}. Matches this codebase's
 * existing, documented convention (CLAUDE.md: "{@code @EnableMethodSecurity} is on, but no
 * controller uses {@code @PreAuthorize} — anyone authenticated can hit any endpoint") rather
 * than inventing a stricter per-tool permission model this phase wasn't asked to design: any
 * call carrying a resolved {@code userId} is authorized. Per-tool data isolation still happens
 * inside each tool's own handler via the same {@code userId.equals(entity.getUserId())} pattern
 * used everywhere else in this codebase (see {@code ai.careerpilot.service.WorkflowService} for
 * the precedent) — this provider only gates "is there a caller at all," not "which rows."
 */
public class DefaultMcpAuthorizationProvider implements McpAuthorizationProvider {

    @Override
    public boolean authorize(McpToolDefinition tool, McpExecutionContext context) {
        return context != null && context.userId() != null;
    }
}
