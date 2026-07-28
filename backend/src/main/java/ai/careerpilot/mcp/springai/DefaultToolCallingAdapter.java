package ai.careerpilot.mcp.springai;

import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpExecutor;
import ai.careerpilot.mcp.McpToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.Map;

/**
 * Phase 10.2 (schema fix: Phase 10.3.1) — the first real {@link ToolCallingAdapter}. Turns one
 * {@link McpToolDefinition} into a Spring AI {@link ToolCallback} backed by {@link
 * org.springframework.ai.tool.function.FunctionToolCallback} (from {@code spring-ai-model},
 * already on the classpath since Phase 9.1's {@code spring-ai-bom} import — no new dependency).
 * Calling the resulting {@code ToolCallback} invokes {@link McpExecutor#execute} synchronously
 * (blocking on the returned {@code Mono} — Spring AI's {@code ToolCallback.call(String)}
 * contract is itself synchronous) with the {@link McpExecutionContext} this adapter was built
 * with, and with whatever arguments the calling LLM actually supplied.
 *
 * <p><b>{@code .inputSchema(...)} is not decorative — it's what lets the LLM know what to
 * pass.</b> The original version of this class only called {@code .inputType(Map.class)},
 * which tells Spring AI how to *deserialize* incoming arguments but produces an empty/generic
 * JSON Schema (reflection over {@code Map.class} has no fixed properties) for what the LLM
 * itself sees when deciding whether/how to call this tool. Caught live: {@code
 * analyze_github_profile}'s real {@code inputSchema} declares a required {@code username}
 * string, but without exposing that schema to the model, every call arrived with zero
 * arguments — the tool's own handler correctly reported "missing required argument 'username'"
 * every time, silently (no exception), so it looked like a successful call in logs/metrics.
 * Passing {@link McpToolDefinition#inputSchema()} through explicitly via {@code .inputSchema(...)}
 * is the fix: {@code .inputType(Map.class)} still governs deserialization, {@code
 * .inputSchema(...)} governs what shape the model is told to produce.
 */
public class DefaultToolCallingAdapter implements ToolCallingAdapter {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolCallingAdapter.class);

    private final McpExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();

    public DefaultToolCallingAdapter(McpExecutor executor) {
        this.executor = executor;
    }

    @Override
    public ToolCallback adapt(McpToolDefinition tool, McpExecutionContext context) {
        FunctionToolCallback.Builder<Map<String, Object>, Object> builder = FunctionToolCallback
                .<Map<String, Object>, Object>builder(tool.toolName(),
                        (Map<String, Object> args) -> executor.execute(tool, args, context).block())
                .description(tool.description())
                .inputType(Map.class);

        String schemaJson = toSchemaJson(tool);
        if (schemaJson != null) {
            builder.inputSchema(schemaJson);
        }
        return builder.build();
    }

    private String toSchemaJson(McpToolDefinition tool) {
        if (tool.inputSchema() == null || tool.inputSchema().isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(tool.inputSchema());
        } catch (Exception e) {
            log.warn("Failed to serialize inputSchema for tool '{}', falling back to reflection-based schema: {}",
                    tool.toolName(), e.toString());
            return null;
        }
    }
}
