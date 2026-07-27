package ai.careerpilot.mcp;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 10.1 — the per-invocation context a future {@link McpExecutor} call would carry.
 * Mirrors the {@code userId}/{@code orgId} shape already used across every controller (see
 * {@code ai.careerpilot.security.AuthenticatedUser}, and CLAUDE.md's "JWT auth carries
 * multi-tenant context") so a future execution layer enforces the same per-user/per-org
 * isolation convention as the rest of the platform, plus the fields an MCP call additionally
 * needs: which conversation it belongs to (for Copilot-originated calls), a trace id (for
 * cross-service correlation), and a per-call timeout.
 *
 * @param userId         matches {@code AuthenticatedUser.userId()}
 * @param orgId          matches {@code AuthenticatedUser.orgId()}
 * @param conversationId nullable — set when the call originates from a Copilot conversation
 * @param traceId        correlation id for logs/metrics across this call's lifetime
 * @param timeout        per-call timeout; a future executor is expected to enforce this
 * @param metadata       free-form extension bag, e.g. tenant-specific routing hints
 */
public record McpExecutionContext(
        UUID userId,
        UUID orgId,
        String conversationId,
        String traceId,
        Duration timeout,
        Map<String, Object> metadata) {
}
