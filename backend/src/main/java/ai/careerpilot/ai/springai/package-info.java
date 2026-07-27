/**
 * Phase 9.1 — Spring AI Foundation.
 *
 * <p>This package is a foundation layer only. Nothing here is called by
 * {@link ai.careerpilot.ai.AiGatewayService}, the Smart Router, LangGraph, or any
 * business service — the existing AI Gateway remains the sole production execution
 * path. These types exist so a future phase can migrate one provider at a time onto
 * Spring AI without a business-layer rewrite, per the "Extend first. Replace later."
 * principle this phase was built under.
 *
 * <h2>Layout</h2>
 * <ul>
 *   <li>{@link ai.careerpilot.ai.springai.SpringAiFoundationProperties} — binds
 *       {@code ai.springai.foundation.*} (see application.yml). {@code enabled}
 *       defaults {@code false}, matching every other dark-shipped phase in this
 *       codebase.</li>
 *   <li>{@link ai.careerpilot.ai.springai.SpringAiConfig} — the only place that
 *       constructs Spring AI's {@code ChatModel}/{@code StreamingChatModel}/
 *       {@code EmbeddingModel} beans. Gated by {@code @ConditionalOnProperty} on
 *       {@code ai.springai.foundation.enabled} — when the flag is off (the
 *       default), none of these beans are even created.</li>
 *   <li>{@link ai.careerpilot.ai.springai.adapter} — a thin adapter layer
 *       ({@code SpringAiChatService}, {@code SpringAiModelFactory},
 *       {@code SpringAiProviderAdapter}) sitting alongside the existing
 *       {@code ai.careerpilot.ai.LlmProvider} hierarchy, not replacing it. Nothing
 *       outside this package references these types.</li>
 *   <li>{@link ai.careerpilot.ai.springai.mcp} — extension-point interfaces only
 *       ({@code McpToolRegistry}, {@code McpToolDefinition}, {@code McpCapability},
 *       {@code ToolExecutionContext}). No implementation, no server connection —
 *       actual MCP wiring (filesystem/Postgres/GitHub tool servers) is explicitly
 *       out of scope for this phase and belongs to Phase 9.3+.</li>
 * </ul>
 *
 * <h2>Migration shape for a future phase</h2>
 * The intended seam is {@code AiGatewayService} itself: business services already
 * depend only on {@code AiGatewayService}, never on a concrete provider (see
 * {@code AiGatewayService}'s own class javadoc). A future migration phase can
 * introduce a Spring-AI-backed {@code LlmProvider} implementation (built from the
 * beans/adapters here) and add it to {@code ai.gateway.order} exactly like any other
 * provider — no controller, DTO, or business-service code needs to change. This
 * package does not build that provider; it only makes the building blocks available.
 */
package ai.careerpilot.ai.springai;
