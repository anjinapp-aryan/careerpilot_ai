/**
 * Phase 10.1 — Enterprise MCP Foundation. A dedicated platform layer for the Model Context
 * Protocol, sitting conceptually between Spring AI and business services in the AI stack:
 *
 * <pre>
 * Business Services → AI Gateway → Capability Registry → Spring AI → Smart Router → Provider Registry → Providers
 * </pre>
 * <pre>
 * (future) Business Services / LangGraph → McpExecutor → Enterprise MCP Platform (this package) → Spring AI
 * </pre>
 *
 * <p><b>This phase builds the platform, not a server.</b> No MCP server (filesystem, PostgreSQL,
 * GitHub, Playwright, Redis, memory, Context7, sequential-thinking, browser) is implemented or
 * connected to. No business functionality changes. No runtime behavior changes. With every
 * {@code mcp.*} flag at its default ({@code false}), no bean in this package is even
 * constructed — see {@link ai.careerpilot.mcp.McpConfig}.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@link ai.careerpilot.mcp.McpCapability} — the capability taxonomy (FILESYSTEM,
 *       DATABASE, BROWSER, GITHUB, MEMORY, SEARCH, AUTOMATION, KNOWLEDGE).</li>
 *   <li>{@link ai.careerpilot.mcp.McpServerDefinition} / {@link ai.careerpilot.mcp.McpToolDefinition}
 *       — plugin metadata: what a server is, what tools it exposes. Pure data, no I/O.</li>
 *   <li>{@link ai.careerpilot.mcp.McpExecutionContext} — the per-call context (user/org/trace/timeout),
 *       mirroring {@code ai.careerpilot.security.AuthenticatedUser}'s multi-tenant shape.</li>
 *   <li>{@link ai.careerpilot.mcp.McpExecutor} — extension-point only. The contract a future tool-
 *       execution engine implements. No implementation exists.</li>
 *   <li>{@link ai.careerpilot.mcp.McpRegistry} / {@link ai.careerpilot.mcp.InMemoryMcpRegistry} —
 *       the plug-and-play registration point. A real, working (if minimal) implementation, not
 *       just an interface, because a registry with nothing to register into it can't be
 *       meaningfully tested or built against. Registering something here never opens a
 *       connection.</li>
 *   <li>{@link ai.careerpilot.mcp.McpHealthManager} / {@link ai.careerpilot.mcp.InMemoryMcpHealthManager} —
 *       health bookkeeping keyed by server name. Nothing calls {@code recordHeartbeat} in this
 *       phase; every lookup returns {@link ai.careerpilot.mcp.McpHealthStatus#UNKNOWN} until a
 *       future phase wires a real heartbeat source.</li>
 *   <li>{@link ai.careerpilot.mcp.McpMetrics} / {@link ai.careerpilot.mcp.NoopMcpMetrics} —
 *       observability contract, no-op implementation only (no Micrometer wiring in this phase).</li>
 *   <li>{@link ai.careerpilot.mcp.McpProperties} / {@link ai.careerpilot.mcp.McpConfig} — the
 *       {@code mcp.enabled} / {@code mcp.discovery.enabled} / {@code mcp.health.enabled} flags
 *       and the only place any bean in this package is constructed.</li>
 *   <li>{@link ai.careerpilot.mcp.security} — authentication/authorization/audit abstractions
 *       every future MCP server inherits automatically instead of rolling its own.</li>
 *   <li>{@link ai.careerpilot.mcp.springai} — extension points for a future Spring AI tool-calling
 *       bridge. Not connected today.</li>
 * </ul>
 *
 * <h2>Relationship to the Phase 9.1 placeholder</h2>
 * {@code ai.careerpilot.ai.springai.mcp} (Phase 9.1) already sketched a narrower, Spring-AI-scoped
 * MCP placeholder ({@code McpToolRegistry}, {@code McpToolDefinition}, {@code McpCapability},
 * {@code ToolExecutionContext}) with zero implementation. This package supersedes it as the
 * platform-wide MCP foundation — richer capability/health/security/observability model, and a
 * real (if in-memory) registry implementation — per this phase's explicit "Suggested package:
 * careerpilot.mcp" instruction. The Phase 9.1 types are left in place (nothing references them,
 * so removing them is out of scope for this additive phase) but new work should build against
 * this package, not that one.
 *
 * <h2>Plugin architecture</h2>
 * Every future MCP server registers itself through {@link ai.careerpilot.mcp.McpRegistry} via a
 * plain {@code @Configuration}/{@code @Bean} — see {@link ai.careerpilot.mcp.McpRegistry}'s own
 * javadoc. No server is ever hardcoded into this platform.
 *
 * <h2>LangGraph integration points (not implemented)</h2>
 * LangGraph itself (the Python {@code agent-service}) is untouched by this phase — no file
 * under {@code agent-service/} changed. The intended future seam is entirely on the Java side:
 * a future business service or LangGraph-adjacent Java component would call {@link
 * ai.careerpilot.mcp.McpExecutor} instead of a concrete tool integration, exactly the way
 * business services already call {@code AiGatewayService} instead of a concrete {@code
 * LlmProvider} (see {@code AiGatewayService}'s own class javadoc for that existing precedent).
 * Nothing calls {@link ai.careerpilot.mcp.McpExecutor} today because nothing implements it.
 *
 * <h2>Business services (unchanged)</h2>
 * {@code ResumeService}, job discovery, company intelligence, career intelligence, the Copilot,
 * and every other business service continue to use their existing implementations exactly as
 * before. None of them are aware this package exists.
 */
package ai.careerpilot.mcp;
