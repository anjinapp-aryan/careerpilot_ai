package ai.careerpilot.mcp.springai;

import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpRegistry;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 10.2 — the first real {@link SpringAiMcpBridge}. Resolves every registered tool for a
 * given {@link McpCapability} into a Spring AI {@link ToolCallback} via {@link
 * ToolCallingAdapter}. <b>Known limitation, by design</b>: {@link
 * SpringAiMcpBridge#toolCallbacksFor} (the Phase 10.1 interface this implements) takes only a
 * capability, not a caller — so the {@link McpExecutionContext} built here is system-scoped
 * ({@code userId=null}), not per-request. Every Phase 10.2 tool handler already treats a
 * {@code null} userId as "no authenticated user" and degrades to an unavailable/empty result
 * (see e.g. {@code FilesystemMcpServerConfig}'s handler) rather than leaking cross-tenant data —
 * so this is safe, but it also means callbacks built through this bridge can't answer
 * per-user questions. A future caller that needs real per-request context should call {@link
 * ToolCallingAdapter#adapt} directly with a context built from the actual request (mirroring
 * {@code CurrentUserResolver}'s {@code AuthenticatedUser}), or a future phase should widen this
 * interface to accept a context parameter. Nothing in this codebase constructs a {@code
 * ChatModel} prompt with these callbacks today — see the package javadoc.
 */
public class DefaultSpringAiMcpBridge implements SpringAiMcpBridge {

    private final McpRegistry registry;
    private final ToolCallingAdapter adapter;

    public DefaultSpringAiMcpBridge(McpRegistry registry, ToolCallingAdapter adapter) {
        this.registry = registry;
        this.adapter = adapter;
    }

    @Override
    public List<ToolCallback> toolCallbacksFor(McpCapability capability) {
        McpExecutionContext systemContext = new McpExecutionContext(
                null, null, null, UUID.randomUUID().toString(), Duration.ofSeconds(20), Map.of());
        return registry.toolsByCapability(capability).stream()
                .map(tool -> adapter.adapt(tool, systemContext))
                .toList();
    }
}
