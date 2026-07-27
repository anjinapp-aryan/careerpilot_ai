package ai.careerpilot.capability;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpExecutor;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.McpToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
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
 * capability), delegate to {@code AiGatewayService.chat(...)} unchanged. Otherwise: execute the
 * selected tools (parallel via {@link CompletableFuture} when {@code
 * parallel.tool.execution.enabled=true}, sequential otherwise — see {@link #executeTools}), merge
 * their results into a context block, then either synthesize the final answer via the Phase 9.1
 * Spring AI foundation {@link ChatModel} (when {@code spring.ai.tool.calling.enabled=true} and
 * the bean exists) or fall back to {@code AiGatewayService.chat(...)} with the context appended
 * to the system prompt — a capability match never fails a request just because Spring AI itself
 * is unavailable.
 */
public class CapabilityAwareChatService {

    private static final Logger log = LoggerFactory.getLogger(CapabilityAwareChatService.class);

    private final AiGatewayService aiGatewayService;
    private final CapabilityEngine capabilityEngine;
    private final ObjectProvider<McpExecutor> mcpExecutorProvider;
    private final ObjectProvider<ChatModel> springAiChatModelProvider;
    private final CapabilityMetrics metrics;
    private final boolean parallelExecutionEnabled;
    private final boolean springAiToolCallingEnabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public CapabilityAwareChatService(AiGatewayService aiGatewayService,
                                       CapabilityEngine capabilityEngine,
                                       ObjectProvider<McpExecutor> mcpExecutorProvider,
                                       ObjectProvider<ChatModel> springAiChatModelProvider,
                                       CapabilityMetrics metrics,
                                       boolean parallelExecutionEnabled,
                                       boolean springAiToolCallingEnabled) {
        this.aiGatewayService = aiGatewayService;
        this.capabilityEngine = capabilityEngine;
        this.mcpExecutorProvider = mcpExecutorProvider;
        this.springAiChatModelProvider = springAiChatModelProvider;
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

        Map<String, McpToolResult> toolResults = executeTools(decision.tools(), executor, context);
        String mergedContext = mergeResults(toolResults);
        metrics.recordMergedContextSize(mergedContext.length());

        ChatModel chatModel = springAiChatModelProvider.getIfAvailable();
        String answer;
        boolean usedSpringAi;
        String fallbackReason = null;
        if (springAiToolCallingEnabled && chatModel != null) {
            try {
                answer = synthesizeWithSpringAi(chatModel, messages, system, mergedContext);
                usedSpringAi = true;
            } catch (Exception e) {
                // Phase 10.4 requirement: "If Spring AI Tool Calling fails -> Fallback ->
                // AiGatewayService". Falling back here (not just letting the exception
                // propagate to a caller like CopilotService) means the tool results already
                // gathered above aren't wasted — they're still appended to the fallback call.
                log.warn("Spring AI tool-calling synthesis failed, falling back to AiGatewayService: {}", e.toString());
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
