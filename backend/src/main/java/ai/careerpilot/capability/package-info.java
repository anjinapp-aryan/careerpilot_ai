/**
 * Phase 10.3 — Enterprise Capability Engine &amp; Spring AI Tool Orchestration. A new,
 * additive orchestration layer that decides, per request, whether to use the existing (fully
 * unmodified) {@link ai.careerpilot.ai.AiGatewayService} or MCP-backed Spring AI tool calling
 * (Phase 10.1/10.2's platform).
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@link ai.careerpilot.capability.CapabilityType} — the fixed capability taxonomy
 *       (resume analysis, job recommendation, GitHub review, career strategy, interview prep,
 *       learning help).</li>
 *   <li>{@link ai.careerpilot.capability.CapabilityDefinition} / {@link
 *       ai.careerpilot.capability.CapabilityRegistry} — declares which MCP capability categories
 *       each {@code CapabilityType} draws from; six defaults pre-registered by {@link
 *       ai.careerpilot.capability.InMemoryCapabilityRegistry}.</li>
 *   <li>{@link ai.careerpilot.capability.CapabilityResolver} — keyword-based request
 *       classification, styled after {@code CopilotSkillRouter#inferSkillFromMessage}.</li>
 *   <li>{@link ai.careerpilot.capability.ToolSelectionEngine} — resolves a matched capability
 *       into concrete, live-registered MCP tools via {@code McpRegistry}.</li>
 *   <li>{@link ai.careerpilot.capability.CapabilityEngine} — the decision chain: message → type
 *       → definition → tools, with every break in that chain producing an explicit "fall back to
 *       AiGatewayService" verdict.</li>
 *   <li>{@link ai.careerpilot.capability.CapabilityAwareChatService} — the actual orchestrator:
 *       executes selected tools (parallel or sequential), merges results, and either synthesizes
 *       via the Phase 9.1 Spring AI foundation {@code ChatModel} or falls back to {@code
 *       AiGatewayService} with the merged context appended.</li>
 *   <li>{@link ai.careerpilot.capability.CapabilityMetrics} — capability selection counts,
 *       decision latency, tool execution time, merged context size, fallback reasons.</li>
 *   <li>{@link ai.careerpilot.capability.CapabilityConfig} — the only place any bean in this
 *       package is constructed, gated by {@code capability.engine.enabled} (default {@code
 *       false}).</li>
 * </ul>
 *
 * <h2>Not wired into any request path</h2>
 * Per the phase spec's "ZERO REST API changes" requirement, no controller constructs or calls
 * {@link ai.careerpilot.capability.CapabilityAwareChatService}. {@code AiGatewayService} remains
 * the sole production chat path; this package is a ready-to-wire orchestration layer sitting
 * alongside it, exactly as Phase 10.1/10.2's MCP platform sits inert alongside the existing AI
 * stack until a future phase connects a controller to it.
 */
package ai.careerpilot.capability;
