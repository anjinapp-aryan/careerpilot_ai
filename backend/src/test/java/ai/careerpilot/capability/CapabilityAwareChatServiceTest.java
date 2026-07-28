package ai.careerpilot.capability;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpExecutor;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.McpToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CapabilityAwareChatService} — the orchestrator. Covers every branch of the "Verify that
 * disabling feature flags restores the original behaviour exactly" requirement: no-capability
 * fallback, MCP-unavailable fallback, both parallel and sequential tool execution, and the
 * spring.ai.tool.calling on/off split for final answer synthesis.
 */
class CapabilityAwareChatServiceTest {

    private final AiGatewayService aiGatewayService = mock(AiGatewayService.class);
    private final InMemoryCapabilityMetrics metrics = new InMemoryCapabilityMetrics();

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerFor(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private McpExecutionContext context() {
        return new McpExecutionContext(UUID.randomUUID(), null, null, "trace", Duration.ofSeconds(5), Map.of());
    }

    private List<ChatMessage> userMessage(String text) {
        return List.of(new ChatMessage("user", text));
    }

    @Test
    void noCapabilityMatch_delegatesToAiGatewayServiceUnchanged() {
        CapabilityEngine engine = message -> CapabilityDecision.noToolNeeded("no capability keyword matched");
        when(aiGatewayService.chat(any(), any())).thenReturn("plain answer");

        CapabilityAwareChatService service = new CapabilityAwareChatService(
                aiGatewayService, engine, providerFor(null), providerFor(null), providerFor(null), providerFor(null), metrics,false, false);

        CapabilityResult result = service.chat(userMessage("hello"), "system", context());

        assertThat(result.usedToolCalling()).isFalse();
        assertThat(result.usedSpringAi()).isFalse();
        assertThat(result.answer()).isEqualTo("plain answer");
        verify(aiGatewayService).chat(userMessage("hello"), "system");
    }

    @Test
    void toolCallingDecidedButMcpExecutorAbsent_fallsBackGracefully() {
        McpToolDefinition tool = new McpToolDefinition("analyze_github_profile", "d", Map.of(), Map.of(), McpCapability.GITHUB, "github");
        CapabilityEngine engine = message -> CapabilityDecision.useTools(CapabilityType.GITHUB_REVIEW, List.of(tool), "matched");
        when(aiGatewayService.chat(any(), any())).thenReturn("fallback answer");

        CapabilityAwareChatService service = new CapabilityAwareChatService(
                aiGatewayService, engine, providerFor(null), providerFor(null), providerFor(null), providerFor(null), metrics,false, false);

        CapabilityResult result = service.chat(userMessage("github please"), "system", context());

        assertThat(result.usedToolCalling()).isFalse();
        assertThat(result.answer()).isEqualTo("fallback answer");
        assertThat(result.fallbackReason()).contains("MCP executor unavailable");
    }

    @Test
    void toolCallingWithExecutor_sequentialExecution_mergesContextAndFallsBackToGatewayForSynthesis() {
        McpToolDefinition tool = new McpToolDefinition("analyze_github_profile", "d", Map.of(), Map.of(), McpCapability.GITHUB, "github");
        CapabilityEngine engine = message -> CapabilityDecision.useTools(CapabilityType.GITHUB_REVIEW, List.of(tool), "matched");
        McpExecutor executor = mock(McpExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(Mono.just(McpToolResult.ok(Map.of("repoCount", 3))));
        when(aiGatewayService.chat(any(), any())).thenReturn("synthesized answer");

        CapabilityAwareChatService service = new CapabilityAwareChatService(
                aiGatewayService, engine, providerFor(executor), providerFor(null), providerFor(null), providerFor(null), metrics,false, false);

        CapabilityResult result = service.chat(userMessage("github please"), "system", context());

        assertThat(result.usedToolCalling()).isTrue();
        assertThat(result.usedSpringAi()).isFalse();
        assertThat(result.toolResults()).containsKey("analyze_github_profile");
        assertThat(result.mergedContext()).contains("repoCount");
        assertThat(result.answer()).isEqualTo("synthesized answer");
        // chatModel provider returns null here, so the "ChatModel unavailable" branch wins over
        // the flag-false branch — see the dedicated flag-false test below for that path.
        assertThat(result.fallbackReason()).contains("ChatModel unavailable");
    }

    @Test
    void springAiToolCallingDisabled_fallsBackEvenWithChatModelPresent() {
        McpToolDefinition tool = new McpToolDefinition("analyze_github_profile", "d", Map.of(), Map.of(), McpCapability.GITHUB, "github");
        CapabilityEngine engine = message -> CapabilityDecision.useTools(CapabilityType.GITHUB_REVIEW, List.of(tool), "matched");
        McpExecutor executor = mock(McpExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(Mono.just(McpToolResult.ok("ok")));
        when(aiGatewayService.chat(any(), any())).thenReturn("gateway answer");
        ChatModel chatModel = mock(ChatModel.class);

        CapabilityAwareChatService service = new CapabilityAwareChatService(
                aiGatewayService, engine, providerFor(executor), providerFor(chatModel), providerFor(null), providerFor(null), metrics,false, false);

        CapabilityResult result = service.chat(userMessage("github please"), "system", context());

        assertThat(result.usedSpringAi()).isFalse();
        assertThat(result.answer()).isEqualTo("gateway answer");
        assertThat(result.fallbackReason()).isEqualTo("spring.ai.tool.calling.enabled=false");
        verify(chatModel, never()).call(any(org.springframework.ai.chat.prompt.Prompt.class));
    }

    @Test
    void springAiSynthesisThrows_fallsBackToGatewayRatherThanPropagating() {
        McpToolDefinition tool = new McpToolDefinition("analyze_github_profile", "d", Map.of(), Map.of(), McpCapability.GITHUB, "github");
        CapabilityEngine engine = message -> CapabilityDecision.useTools(CapabilityType.GITHUB_REVIEW, List.of(tool), "matched");
        McpExecutor executor = mock(McpExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(Mono.just(McpToolResult.ok("ok")));
        when(aiGatewayService.chat(any(), any())).thenReturn("gateway saved the day");

        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("provider unreachable"));

        CapabilityAwareChatService service = new CapabilityAwareChatService(
                aiGatewayService, engine, providerFor(executor), providerFor(chatModel), providerFor(null), providerFor(null), metrics,false, true);

        CapabilityResult result = service.chat(userMessage("github please"), "system", context());

        assertThat(result.usedSpringAi()).isFalse();
        assertThat(result.answer()).isEqualTo("gateway saved the day");
        assertThat(result.fallbackReason()).contains("Spring AI tool calling failed");
    }

    @Test
    void parallelExecutionEnabled_executesAllToolsAndMergesAllResults() {
        McpToolDefinition tool1 = new McpToolDefinition("get_job_recommendations", "d", Map.of(), Map.of(), McpCapability.DATABASE, "postgres");
        McpToolDefinition tool2 = new McpToolDefinition("get_career_memory_summary", "d", Map.of(), Map.of(), McpCapability.MEMORY, "memory");
        CapabilityEngine engine = message -> CapabilityDecision.useTools(CapabilityType.CAREER_STRATEGY, List.of(tool1, tool2), "matched");
        McpExecutor executor = mock(McpExecutor.class);
        when(executor.execute(any(), any(), any()))
                .thenReturn(Mono.just(McpToolResult.ok("ok")));
        when(aiGatewayService.chat(any(), any())).thenReturn("answer");

        CapabilityAwareChatService service = new CapabilityAwareChatService(
                aiGatewayService, engine, providerFor(executor), providerFor(null), providerFor(null), providerFor(null), metrics,true, false);

        CapabilityResult result = service.chat(userMessage("career strategy please"), "system", context());

        assertThat(result.toolResults()).hasSize(2);
        assertThat(result.toolResults()).containsKeys("get_job_recommendations", "get_career_memory_summary");
        verify(executor, times(2)).execute(any(), any(), any());
    }

    @Test
    void toolThrows_stillProducesFailedResultRatherThanPropagating() {
        McpToolDefinition tool = new McpToolDefinition("analyze_github_profile", "d", Map.of(), Map.of(), McpCapability.GITHUB, "github");
        CapabilityEngine engine = message -> CapabilityDecision.useTools(CapabilityType.GITHUB_REVIEW, List.of(tool), "matched");
        McpExecutor executor = mock(McpExecutor.class);
        when(executor.execute(any(), any(), any())).thenThrow(new RuntimeException("network down"));
        when(aiGatewayService.chat(any(), any())).thenReturn("answer despite tool failure");

        CapabilityAwareChatService service = new CapabilityAwareChatService(
                aiGatewayService, engine, providerFor(executor), providerFor(null), providerFor(null), providerFor(null), metrics,false, false);

        CapabilityResult result = service.chat(userMessage("github please"), "system", context());

        assertThat(result.toolResults().get("analyze_github_profile").success()).isFalse();
        assertThat(result.answer()).isEqualTo("answer despite tool failure");
    }

    @Test
    void springAiToolCallingEnabledButChatModelAbsent_fallsBackToGateway() {
        McpToolDefinition tool = new McpToolDefinition("analyze_github_profile", "d", Map.of(), Map.of(), McpCapability.GITHUB, "github");
        CapabilityEngine engine = message -> CapabilityDecision.useTools(CapabilityType.GITHUB_REVIEW, List.of(tool), "matched");
        McpExecutor executor = mock(McpExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(Mono.just(McpToolResult.ok("ok")));
        when(aiGatewayService.chat(any(), any())).thenReturn("gateway answer");

        CapabilityAwareChatService service = new CapabilityAwareChatService(
                aiGatewayService, engine, providerFor(executor), providerFor(null), providerFor(null), providerFor(null), metrics,false, true);

        CapabilityResult result = service.chat(userMessage("github please"), "system", context());

        assertThat(result.usedSpringAi()).isFalse();
        assertThat(result.answer()).isEqualTo("gateway answer");
        assertThat(result.fallbackReason()).contains("ChatModel unavailable");
    }

    @Test
    void springAiToolCallingEnabledAndChatModelPresent_usesSpringAiNotGateway() {
        McpToolDefinition tool = new McpToolDefinition("analyze_github_profile", "d", Map.of(), Map.of(), McpCapability.GITHUB, "github");
        CapabilityEngine engine = message -> CapabilityDecision.useTools(CapabilityType.GITHUB_REVIEW, List.of(tool), "matched");
        McpExecutor executor = mock(McpExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(Mono.just(McpToolResult.ok("ok")));

        ChatModel chatModel = mock(ChatModel.class);
        org.springframework.ai.chat.model.ChatResponse response = mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation generation = mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage assistantMessage = new org.springframework.ai.chat.messages.AssistantMessage("spring ai answer");
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistantMessage);

        CapabilityAwareChatService service = new CapabilityAwareChatService(
                aiGatewayService, engine, providerFor(executor), providerFor(chatModel), providerFor(null), providerFor(null), metrics,false, true);

        CapabilityResult result = service.chat(userMessage("github please"), "system", context());

        assertThat(result.usedSpringAi()).isTrue();
        assertThat(result.answer()).isEqualTo("spring ai answer");
        verify(aiGatewayService, never()).chat(any(), anyString());
    }

    @Test
    void realToolCallingAvailable_preferredOverLegacyPreExecutePath() {
        McpToolDefinition tool = new McpToolDefinition("analyze_github_profile", "d", Map.of(), Map.of(), McpCapability.GITHUB, "github");
        CapabilityEngine engine = message -> CapabilityDecision.useTools(CapabilityType.GITHUB_REVIEW, List.of(tool), "matched");
        McpExecutor executor = mock(McpExecutor.class);

        ChatModel chatModel = mock(ChatModel.class);
        org.springframework.ai.chat.model.ChatResponse response = mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation generation = mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage assistantMessage =
                new org.springframework.ai.chat.messages.AssistantMessage("real tool-calling answer");
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistantMessage);

        ai.careerpilot.mcp.springai.ToolCallingAdapter adapter = mock(ai.careerpilot.mcp.springai.ToolCallingAdapter.class);
        org.springframework.ai.tool.ToolCallback callback = mock(org.springframework.ai.tool.ToolCallback.class);
        when(adapter.adapt(any(), any())).thenReturn(callback);

        CapabilityAwareChatService service = new CapabilityAwareChatService(
                aiGatewayService, engine, providerFor(executor), providerFor(chatModel), providerFor(adapter), providerFor(null), metrics, false, true);

        CapabilityResult result = service.chat(userMessage("github please, username torvalds"), "system", context());

        assertThat(result.usedSpringAi()).isTrue();
        assertThat(result.answer()).isEqualTo("real tool-calling answer");
        verify(adapter).adapt(eq(tool), any());
        // Real tool calling lets Spring AI's internal loop invoke tools via the callback itself —
        // this class never pre-executes them with an empty argument map for this path.
        verify(executor, never()).execute(any(), any(), any());
        verify(aiGatewayService, never()).chat(any(), anyString());
    }

    @Test
    void realToolCallingThrows_fallsBackToLegacyPreExecutePath() {
        McpToolDefinition tool = new McpToolDefinition("analyze_github_profile", "d", Map.of(), Map.of(), McpCapability.GITHUB, "github");
        CapabilityEngine engine = message -> CapabilityDecision.useTools(CapabilityType.GITHUB_REVIEW, List.of(tool), "matched");
        McpExecutor executor = mock(McpExecutor.class);
        when(executor.execute(any(), any(), any())).thenReturn(Mono.just(McpToolResult.ok("ok")));

        ChatModel chatModel = mock(ChatModel.class);
        org.springframework.ai.chat.model.ChatResponse response = mock(org.springframework.ai.chat.model.ChatResponse.class);
        org.springframework.ai.chat.model.Generation generation = mock(org.springframework.ai.chat.model.Generation.class);
        org.springframework.ai.chat.messages.AssistantMessage assistantMessage =
                new org.springframework.ai.chat.messages.AssistantMessage("legacy path answer");
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistantMessage);

        ai.careerpilot.mcp.springai.ToolCallingAdapter adapter = mock(ai.careerpilot.mcp.springai.ToolCallingAdapter.class);
        when(adapter.adapt(any(), any())).thenThrow(new RuntimeException("schema serialization failed"));

        CapabilityAwareChatService service = new CapabilityAwareChatService(
                aiGatewayService, engine, providerFor(executor), providerFor(chatModel), providerFor(adapter), providerFor(null), metrics, false, true);

        CapabilityResult result = service.chat(userMessage("github please"), "system", context());

        assertThat(result.usedSpringAi()).isTrue();
        assertThat(result.answer()).isEqualTo("legacy path answer");
        // Legacy fallback pre-executes the tool (unlike the real-tool-calling path above).
        verify(executor).execute(any(), any(), any());
        assertThat(result.toolResults()).containsKey("analyze_github_profile");
    }
}
