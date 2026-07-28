package ai.careerpilot.capability;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.ai.springai.SpringAiFoundationProperties;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpExecutor;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.McpToolResult;
import ai.careerpilot.mcp.springai.ToolCallingAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Phase 10.3 — the new orchestration entry point named in the phase spec. <b>Not called by any
 * controller</b> — per the spec's "ZERO REST API changes" requirement, this class exists as a
 * ready-to-wire alternative to calling {@link AiGatewayService} directly, exactly like the MCP
 * platform (Phase 10.1/10.2) it sits on top of. {@link AiGatewayService} itself is a plain,
 * unmodified constructor dependency here — this class never subclasses it, never reimplements
 * its routing, and is the ONLY new code that calls it differently (by appending merged tool
 * context to the system prompt on the "tools found, but Spring AI synthesis disabled" path).
 *
 * <p>Decision chain per call: {@link CapabilityEngine#analyze} → if no tool calling needed
 * (no capability matched, {@code tool.selection.enabled=false}, or no tools registered for the
 * capability), delegate to {@code AiGatewayService.chat(...)} unchanged. Otherwise, prefer
 * <b>real Spring AI tool calling</b> ({@link #synthesizeWithRealToolCalling}) — the LLM itself
 * decides which tools to call and with what arguments from each tool's declared JSON schema —
 * when {@code spring.ai.tool.calling.enabled=true} and both the Phase 9.1 {@link ChatModel} and
 * the Phase 10.2 {@code ToolCallingAdapter} beans exist. Anything that stops that path (flag
 * off, a missing bean, or the call itself throwing) falls through to the pre-Phase-10.3.1
 * legacy path ({@link #legacyPreExecuteAndRespond}): pre-execute the selected tools with an
 * empty argument map (parallel via {@link CompletableFuture} when {@code
 * parallel.tool.execution.enabled=true}, sequential otherwise — see {@link #executeTools}),
 * merge results into a context block, then synthesize via the {@link ChatModel} with that
 * context appended to the system prompt, or fall back further to {@code
 * AiGatewayService.chat(...)} — a capability match never fails a request outright.
 */
public class CapabilityAwareChatService {

    private static final Logger log = LoggerFactory.getLogger(CapabilityAwareChatService.class);

    private final AiGatewayService aiGatewayService;
    private final CapabilityEngine capabilityEngine;
    private final ObjectProvider<McpExecutor> mcpExecutorProvider;
    private final ObjectProvider<ChatModel> springAiChatModelProvider;
    private final ObjectProvider<ToolCallingAdapter> toolCallingAdapterProvider;
    private final ObjectProvider<SpringAiFoundationProperties> springAiFoundationPropertiesProvider;
    private final CapabilityMetrics metrics;
    private final boolean parallelExecutionEnabled;
    private final boolean springAiToolCallingEnabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public CapabilityAwareChatService(AiGatewayService aiGatewayService,
                                       CapabilityEngine capabilityEngine,
                                       ObjectProvider<McpExecutor> mcpExecutorProvider,
                                       ObjectProvider<ChatModel> springAiChatModelProvider,
                                       ObjectProvider<ToolCallingAdapter> toolCallingAdapterProvider,
                                       ObjectProvider<SpringAiFoundationProperties> springAiFoundationPropertiesProvider,
                                       CapabilityMetrics metrics,
                                       boolean parallelExecutionEnabled,
                                       boolean springAiToolCallingEnabled) {
        this.aiGatewayService = aiGatewayService;
        this.capabilityEngine = capabilityEngine;
        this.mcpExecutorProvider = mcpExecutorProvider;
        this.springAiChatModelProvider = springAiChatModelProvider;
        this.toolCallingAdapterProvider = toolCallingAdapterProvider;
        this.springAiFoundationPropertiesProvider = springAiFoundationPropertiesProvider;
        this.metrics = metrics;
        this.parallelExecutionEnabled = parallelExecutionEnabled;
        this.springAiToolCallingEnabled = springAiToolCallingEnabled;
    }

    public CapabilityResult chat(List<ChatMessage> messages, String system, McpExecutionContext context) {
        long start = System.currentTimeMillis();
        String lastUserMessage = lastUserMessage(messages);
        CapabilityDecision decision = capabilityEngine.analyze(lastUserMessage);

        McpExecutor executor = mcpExecutorProvider.getIfAvailable();
        if (!decision.useToolCalling() || executor == null) {
            String reason = executor == null && decision.useToolCalling()
                    ? "MCP executor unavailable (mcp.enabled=false)" : decision.reason();
            metrics.recordFallback(reason);
            String answer = aiGatewayService.chat(messages, system);
            return new CapabilityResult(decision.capabilityType(), Map.of(), "", answer,
                    false, false, System.currentTimeMillis() - start, reason);
        }

        // Real tool calling: the LLM itself decides which tools to call and with what
        // arguments, based on each tool's declared JSON schema (see DefaultToolCallingAdapter).
        // Preferred whenever available — it's the only path that actually threads real,
        // model-chosen arguments (e.g. GitHub's "username") into the tool call. Anything that
        // stops this path from working (flag off, no ChatModel bean, no ToolCallingAdapter
        // bean, or the call itself throwing) falls through to the legacy pre-execute path below.
        ChatModel chatModel = springAiChatModelProvider.getIfAvailable();
        ToolCallingAdapter toolCallingAdapter = toolCallingAdapterProvider.getIfAvailable();
        if (springAiToolCallingEnabled && chatModel != null && toolCallingAdapter != null) {
            try {
                String answer = synthesizeWithRealToolCalling(chatModel, toolCallingAdapter, decision.tools(), messages, system, context);
                return new CapabilityResult(decision.capabilityType(), Map.of(), "", answer,
                        true, true, System.currentTimeMillis() - start, null);
            } catch (Exception e) {
                // Phase 10.4 requirement: "If Spring AI Tool Calling fails -> Fallback ->
                // AiGatewayService". Fall through to the legacy pre-execute path rather than
                // propagating, matching every other engine's never-fail-the-request discipline.
                log.warn("Spring AI real tool-calling failed, falling back to legacy pre-execute path: {}", e.toString());
                metrics.recordFallback("Spring AI real tool calling failed: " + e);
            }
        }

        return legacyPreExecuteAndRespond(decision, executor, context, messages, system, start, chatModel);
    }

    /**
     * Pre-Phase-10.3.1 behavior, kept as the fallback path: pre-execute every selected tool with
     * an empty argument map (fine for zero-argument tools, silently wrong for anything requiring
     * real input — this is exactly why real tool calling above is preferred whenever available),
     * merge the results into a text block, and either synthesize via the Spring AI {@link
     * ChatModel} (context appended to the system prompt — the old "manual" synthesis) or fall
     * back further to {@link AiGatewayService}.
     */
    private CapabilityResult legacyPreExecuteAndRespond(CapabilityDecision decision, McpExecutor executor,
                                                          McpExecutionContext context, List<ChatMessage> messages,
                                                          String system, long start, ChatModel chatModel) {
        Map<String, McpToolResult> toolResults = executeTools(decision.tools(), executor, context);
        String mergedContext = mergeResults(toolResults);
        metrics.recordMergedContextSize(mergedContext.length());

        String answer;
        boolean usedSpringAi;
        String fallbackReason;
        if (springAiToolCallingEnabled && chatModel != null) {
            try {
                answer = synthesizeWithSpringAi(chatModel, messages, system, mergedContext);
                usedSpringAi = true;
                fallbackReason = null;
            } catch (Exception e) {
                log.warn("Spring AI context-synthesis failed, falling back to AiGatewayService: {}", e.toString());
                fallbackReason = "Spring AI tool calling failed: " + e;
                metrics.recordFallback(fallbackReason);
                answer = aiGatewayService.chat(appendContext(messages, mergedContext), system);
                usedSpringAi = false;
            }
        } else {
            fallbackReason = chatModel == null
                    ? "Spring AI foundation ChatModel unavailable (ai.springai.foundation.enabled=false)"
                    : "spring.ai.tool.calling.enabled=false";
            metrics.recordFallback(fallbackReason);
            answer = aiGatewayService.chat(appendContext(messages, mergedContext), system);
            usedSpringAi = false;
        }

        return new CapabilityResult(decision.capabilityType(), toolResults, mergedContext, answer,
                true, usedSpringAi, System.currentTimeMillis() - start, fallbackReason);
    }

    /**
     * Real Spring AI tool calling: builds one {@link ToolCallback} per selected tool via {@link
     * ToolCallingAdapter#adapt}, using the {@link McpExecutionContext} of the actual request
     * (unlike {@code DefaultSpringAiMcpBridge}'s system-scoped context, this class always has a
     * real per-user context available). {@code OpenAiChatOptions.toolCallbacks(...)} hands those
     * callbacks to Spring AI, which runs its own internal loop: call the model, detect any
     * tool_calls in the response, invoke the matching callback (which itself calls {@link
     * McpExecutor#execute} — the same executor, metrics, and audit trail as every other path),
     * feed the tool result back to the model, and repeat until the model returns final text — all
     * inside this single {@code chatModel.call(...)}.
     */
    private String synthesizeWithRealToolCalling(ChatModel chatModel, ToolCallingAdapter toolCallingAdapter,
                                                   List<McpToolDefinition> tools, List<ChatMessage> messages,
                                                   String system, McpExecutionContext context) {
        List<ToolCallback> callbacks = tools.stream()
                .map(tool -> toolCallingAdapter.adapt(tool, context))
                .collect(Collectors.toList());

        List<Message> out = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            out.add(new SystemMessage(system));
        }
        for (ChatMessage m : messages) {
            out.add("model".equals(m.role()) ? new AssistantMessage(m.content()) : new UserMessage(m.content()));
        }

        // A Prompt's own ChatOptions REPLACE the ChatModel bean's default options rather than
        // merging with them (verified live: omitting .model(...) here silently sent Spring AI's
        // own hardcoded fallback model name instead of the configured one, which NVIDIA then
        // 404'd on as an unknown model — sent "gpt-5-mini" instead of the configured
        // "deepseek-ai/deepseek-v4-flash"). Carrying the configured model name forward
        // explicitly is required, not optional.
        SpringAiFoundationProperties props = springAiFoundationPropertiesProvider.getIfAvailable();
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder().toolCallbacks(callbacks);
        if (props != null && props.getChatModel() != null) {
            optionsBuilder.model(props.getChatModel());
        }
        return chatModel.call(new Prompt(out, optionsBuilder.build())).getResult().getOutput().getText();
    }

    private Map<String, McpToolResult> executeTools(List<McpToolDefinition> tools, McpExecutor executor, McpExecutionContext context) {
        if (parallelExecutionEnabled) {
            Map<String, CompletableFuture<McpToolResult>> futures = tools.stream()
                    .collect(Collectors.toMap(McpToolDefinition::toolName,
                            tool -> executeOneAsync(tool, executor, context)));
            CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();
            Map<String, McpToolResult> results = new LinkedHashMap<>();
            futures.forEach((name, future) -> results.put(name, future.join()));
            return results;
        }
        Map<String, McpToolResult> results = new LinkedHashMap<>();
        for (McpToolDefinition tool : tools) {
            results.put(tool.toolName(), executeOne(tool, executor, context));
        }
        return results;
    }

    private CompletableFuture<McpToolResult> executeOneAsync(McpToolDefinition tool, McpExecutor executor, McpExecutionContext context) {
        return CompletableFuture.supplyAsync(() -> executeOne(tool, executor, context));
    }

    private McpToolResult executeOne(McpToolDefinition tool, McpExecutor executor, McpExecutionContext context) {
        long start = System.currentTimeMillis();
        McpToolResult result;
        try {
            result = executor.execute(tool, Map.of(), context).block();
            if (result == null) {
                result = McpToolResult.failed("tool returned no result");
            }
        } catch (Exception e) {
            log.warn("Capability tool execution failed for '{}': {}", tool.toolName(), e.toString());
            result = McpToolResult.failed(e.toString());
        }
        metrics.recordToolExecutionTime(tool.toolName(), System.currentTimeMillis() - start);
        return result;
    }

    private String mergeResults(Map<String, McpToolResult> results) {
        StringBuilder sb = new StringBuilder();
        results.forEach((toolName, result) -> {
            sb.append("### ").append(toolName).append('\n');
            try {
                sb.append(result.success() ? mapper.writeValueAsString(result.output()) : "unavailable: " + result.error());
            } catch (Exception e) {
                sb.append(String.valueOf(result.output()));
            }
            sb.append("\n\n");
        });
        return sb.toString();
    }

    private String synthesizeWithSpringAi(ChatModel chatModel, List<ChatMessage> messages, String system, String mergedContext) {
        List<Message> out = new ArrayList<>();
        String systemWithContext = (system == null ? "" : system + "\n\n")
                + "Context from tools:\n" + mergedContext;
        out.add(new SystemMessage(systemWithContext));
        for (ChatMessage m : messages) {
            out.add("model".equals(m.role()) ? new AssistantMessage(m.content()) : new UserMessage(m.content()));
        }
        return chatModel.call(new Prompt(out)).getResult().getOutput().getText();
    }

    private List<ChatMessage> appendContext(List<ChatMessage> messages, String mergedContext) {
        if (messages.isEmpty()) {
            return messages;
        }
        List<ChatMessage> copy = new ArrayList<>(messages);
        ChatMessage last = copy.get(copy.size() - 1);
        copy.set(copy.size() - 1, new ChatMessage(last.role(), last.content() + "\n\n[Tool context]\n" + mergedContext));
        return copy;
    }

    private String lastUserMessage(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return messages.get(i).content();
            }
        }
        return messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
    }
}
