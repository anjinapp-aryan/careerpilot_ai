/**
 * Phase 9.1 — MCP (Model Context Protocol) extension points, definitions only.
 *
 * <p>Nothing in this package connects to an MCP server, implements
 * {@link ai.careerpilot.ai.springai.mcp.McpToolRegistry}, or executes a tool. It
 * exists so a future phase has a stable set of types to build against:
 * {@link ai.careerpilot.ai.springai.mcp.McpCapability} (what kind of tool),
 * {@link ai.careerpilot.ai.springai.mcp.McpToolDefinition} (a tool's metadata),
 * {@link ai.careerpilot.ai.springai.mcp.ToolExecutionContext} (the caller's identity
 * and attributes, mirroring {@code ai.careerpilot.security.AuthenticatedUser}), and
 * {@link ai.careerpilot.ai.springai.mcp.McpToolRegistry} (lookup contract).
 *
 * <h2>Planned future integration points (Phase 9.3+, not this phase)</h2>
 * <ul>
 *   <li><b>Filesystem MCP</b> — would back a {@code McpCapability.FILESYSTEM_ACCESS}
 *       tool, e.g. for resume/document artifacts already stored via
 *       {@code ai.careerpilot.storage}. Not implemented.</li>
 *   <li><b>PostgreSQL MCP</b> — would back {@code McpCapability.DATABASE_ACCESS}
 *       against the same shared Postgres this backend already uses (see CLAUDE.md,
 *       "Database is shared but logically partitioned"). Not implemented.</li>
 *   <li><b>GitHub MCP</b> — would back {@code McpCapability.GIT_ACCESS}, plausibly
 *       relevant to a future "explain this candidate's OSS contributions" skill.
 *       Not implemented.</li>
 * </ul>
 *
 * <p>None of the above are connected, scaffolded beyond the interfaces in this
 * package, or scheduled within Phase 9.1 — they are documented here only so a future
 * phase's scope is unambiguous.
 */
package ai.careerpilot.ai.springai.mcp;
