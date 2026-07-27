/**
 * Phase 10.1 — Spring AI ↔ MCP integration extension points: {@link
 * ai.careerpilot.mcp.springai.SpringAiMcpBridge}, {@link ai.careerpilot.mcp.springai.McpToolResolver},
 * {@link ai.careerpilot.mcp.springai.ToolCallingAdapter}. Interfaces only — nothing here is
 * called by {@link ai.careerpilot.ai.springai.SpringAiConfig}, any {@code ChatModel} bean, or
 * {@code AiGatewayService}. Per the phase spec: "Prepare extension points. Spring AI should
 * later discover MCP tools. Do NOT connect today."
 */
package ai.careerpilot.mcp.springai;
